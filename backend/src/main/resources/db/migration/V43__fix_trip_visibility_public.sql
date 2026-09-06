UPDATE trips
SET visibility = 'public'
WHERE visibility <> 'public';

ALTER TABLE trips
    ALTER COLUMN visibility SET DEFAULT 'public';
