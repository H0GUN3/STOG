ALTER TABLE place_source_images
    ADD COLUMN IF NOT EXISTS object_key TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'place_source_images_object_key_unique'
          AND conrelid = 'place_source_images'::regclass
    ) THEN
        ALTER TABLE place_source_images
            ADD CONSTRAINT place_source_images_object_key_unique
                UNIQUE (object_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'place_source_images_object_key_check'
          AND conrelid = 'place_source_images'::regclass
    ) THEN
        ALTER TABLE place_source_images
            ADD CONSTRAINT place_source_images_object_key_check
                CHECK (
                    object_key IS NULL
                    OR object_key ~ '^catalog-images/v1/source-images/[1-9][0-9]*$'
                );
    END IF;
END
$$;
