ALTER TABLE place_source_images
    ADD COLUMN object_key TEXT,
    ADD CONSTRAINT place_source_images_object_key_unique
        UNIQUE (object_key),
    ADD CONSTRAINT place_source_images_object_key_check
        CHECK (
            object_key IS NULL
            OR object_key ~ '^catalog-images/v1/source-images/[1-9][0-9]*$'
        );
