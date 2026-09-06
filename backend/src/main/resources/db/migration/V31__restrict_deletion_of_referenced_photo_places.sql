ALTER TABLE photos
    DROP CONSTRAINT photos_place_id_fkey,
    ADD CONSTRAINT photos_place_id_fkey
        FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE RESTRICT;
