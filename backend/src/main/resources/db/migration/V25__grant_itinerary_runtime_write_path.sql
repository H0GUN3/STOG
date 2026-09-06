GRANT SELECT, INSERT ON trip_itinerary_states TO stog_app;

GRANT SELECT, INSERT, DELETE ON itinerary_items TO stog_app;
GRANT UPDATE (
    day_number,
    order_index,
    planned_arrival,
    planned_duration_min
) ON itinerary_items TO stog_app;

GRANT SELECT, INSERT ON itinerary_changes TO stog_app;
GRANT SELECT, INSERT ON travel_guide_ai_proposals TO stog_app;
GRANT SELECT, INSERT ON travel_guide_ai_proposal_actions TO stog_app;
GRANT SELECT, INSERT ON travel_guide_ai_proposal_exclusions TO stog_app;
GRANT SELECT, INSERT ON travel_guide_ai_proposal_violations TO stog_app;

CREATE FUNCTION reject_fixed_itinerary_item_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.is_fixed THEN
        RAISE EXCEPTION 'fixed itinerary item cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER fixed_itinerary_item_delete_guard
BEFORE DELETE ON itinerary_items
FOR EACH ROW EXECUTE FUNCTION reject_fixed_itinerary_item_delete();
