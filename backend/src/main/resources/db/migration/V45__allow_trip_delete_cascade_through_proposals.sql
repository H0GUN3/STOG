ALTER TABLE travel_guide_ai_proposals
    DROP CONSTRAINT travel_guide_ai_proposals_trip_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposals_trip_id_fkey
        FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    DROP CONSTRAINT travel_guide_ai_proposals_applied_change_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposals_applied_change_id_fkey
        FOREIGN KEY (applied_change_id) REFERENCES itinerary_changes(id) ON DELETE CASCADE;

ALTER TABLE travel_guide_ai_proposal_actions
    DROP CONSTRAINT travel_guide_ai_proposal_actions_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_actions_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE;

ALTER TABLE travel_guide_ai_proposal_exclusions
    DROP CONSTRAINT travel_guide_ai_proposal_exclusions_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_exclusions_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE;

ALTER TABLE travel_guide_ai_proposal_violations
    DROP CONSTRAINT travel_guide_ai_proposal_violations_proposal_id_fkey,
    ADD CONSTRAINT travel_guide_ai_proposal_violations_proposal_id_fkey
        FOREIGN KEY (proposal_id) REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION guard_proposal_receipt_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    valid_change BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
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
