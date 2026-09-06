ALTER TABLE places
    ADD COLUMN public_cell_eligible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX places_public_cell_eligible_idx
    ON places(cell_id)
    WHERE public_cell_eligible
      AND catalog_status = 'public'
      AND cell_id IS NOT NULL;
