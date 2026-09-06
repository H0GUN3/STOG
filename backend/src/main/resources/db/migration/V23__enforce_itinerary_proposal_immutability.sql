ALTER TABLE travel_guide_ai_proposals
    DROP CONSTRAINT travel_guide_ai_proposals_trip_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposals_trip_id_fkey
        FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE RESTRICT,
    DROP CONSTRAINT travel_guide_ai_proposals_applied_change_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposals_applied_change_id_fkey
        FOREIGN KEY (applied_change_id) REFERENCES itinerary_changes(id) ON DELETE RESTRICT;

ALTER TABLE travel_guide_ai_proposal_actions
    DROP CONSTRAINT travel_guide_ai_proposal_actions_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_actions_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE RESTRICT;

ALTER TABLE travel_guide_ai_proposal_exclusions
    DROP CONSTRAINT travel_guide_ai_proposal_exclusions_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_exclusions_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE RESTRICT;

ALTER TABLE travel_guide_ai_proposal_violations
    DROP CONSTRAINT travel_guide_ai_proposal_violations_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_violations_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE RESTRICT;

CREATE FUNCTION reject_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER itinerary_changes_append_only
BEFORE UPDATE OR DELETE ON itinerary_changes
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER proposal_actions_append_only
BEFORE UPDATE OR DELETE ON travel_guide_ai_proposal_actions
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER proposal_exclusions_append_only
BEFORE UPDATE OR DELETE ON travel_guide_ai_proposal_exclusions
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER proposal_violations_append_only
BEFORE UPDATE OR DELETE ON travel_guide_ai_proposal_violations
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE FUNCTION guard_proposal_receipt_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    valid_change BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'travel_guide_ai_proposals is append only';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.trip_id IS DISTINCT FROM OLD.trip_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.base_version IS DISTINCT FROM OLD.base_version
       OR NEW.proposal_fingerprint IS DISTINCT FROM OLD.proposal_fingerprint
       OR NEW.feasible IS DISTINCT FROM OLD.feasible
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'proposal identity is immutable';
    END IF;

    IF OLD.status <> 'ready'
       OR NEW.status <> 'applied'
       OR OLD.applied_client_id IS NOT NULL
       OR OLD.applied_payload_fingerprint IS NOT NULL
       OR OLD.applied_change_id IS NOT NULL
       OR OLD.applied_version IS NOT NULL
       OR OLD.applied_at IS NOT NULL
       OR NEW.applied_client_id IS NULL
       OR NEW.applied_payload_fingerprint IS DISTINCT FROM OLD.proposal_fingerprint
       OR NEW.applied_change_id IS NULL
       OR NEW.applied_version IS DISTINCT FROM OLD.base_version + 1
       OR NEW.applied_at IS NULL THEN
        RAISE EXCEPTION 'proposal permits one complete single apply receipt transition';
    END IF;

    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %I.itinerary_changes WHERE id = $1 AND trip_id = $2)',
        TG_TABLE_SCHEMA
    ) INTO valid_change USING NEW.applied_change_id, OLD.trip_id;
    IF NOT valid_change THEN
        RAISE EXCEPTION 'proposal apply receipt must reference its trip history';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER proposal_receipt_transition_guard
BEFORE UPDATE OR DELETE ON travel_guide_ai_proposals
FOR EACH ROW EXECUTE FUNCTION guard_proposal_receipt_transition();

CREATE FUNCTION guard_itinerary_item_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.trip_id IS DISTINCT FROM OLD.trip_id
       OR NEW.basket_item_id IS DISTINCT FROM OLD.basket_item_id
       OR NEW.is_fixed IS DISTINCT FROM OLD.is_fixed THEN
        RAISE EXCEPTION 'itinerary item identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER itinerary_item_identity_guard
BEFORE UPDATE ON itinerary_items
FOR EACH ROW EXECUTE FUNCTION guard_itinerary_item_identity();

CREATE FUNCTION guard_itinerary_version_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() < 2 THEN
            RAISE EXCEPTION 'itinerary version identity is immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.trip_id IS DISTINCT FROM OLD.trip_id THEN
        RAISE EXCEPTION 'itinerary version identity is immutable';
    END IF;
    IF pg_trigger_depth() < 2 OR NEW.version IS DISTINCT FROM OLD.version + 1 THEN
        RAISE EXCEPTION 'itinerary version requires an append-only history transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER itinerary_version_transition_guard
BEFORE UPDATE OR DELETE ON trip_itinerary_states
FOR EACH ROW EXECUTE FUNCTION guard_itinerary_version_transition();

CREATE FUNCTION advance_itinerary_version_from_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    changed INTEGER;
BEGIN
    EXECUTE format(
        'UPDATE %I.trip_itinerary_states SET version = version + 1 WHERE trip_id = $1',
        TG_TABLE_SCHEMA
    ) USING NEW.trip_id;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 1 THEN
        RAISE EXCEPTION 'itinerary history requires one version state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER itinerary_history_advances_version
AFTER INSERT ON itinerary_changes
FOR EACH ROW EXECUTE FUNCTION advance_itinerary_version_from_history();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stog_app') THEN
        REVOKE UPDATE, DELETE ON itinerary_changes FROM stog_app;
        REVOKE UPDATE, DELETE ON travel_guide_ai_proposal_actions FROM stog_app;
        REVOKE UPDATE, DELETE ON travel_guide_ai_proposal_exclusions FROM stog_app;
        REVOKE UPDATE, DELETE ON travel_guide_ai_proposal_violations FROM stog_app;
        REVOKE UPDATE, DELETE ON travel_guide_ai_proposals FROM stog_app;
        GRANT UPDATE (
            status, applied_client_id, applied_payload_fingerprint,
            applied_change_id, applied_version, applied_at
        ) ON travel_guide_ai_proposals TO stog_app;
        REVOKE UPDATE ON itinerary_items FROM stog_app;
        REVOKE UPDATE, DELETE ON trip_itinerary_states FROM stog_app;
    END IF;
END;
$$;
