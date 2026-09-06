ALTER TABLE trips
    ADD COLUMN region_code TEXT NOT NULL DEFAULT 'JEONBUK';

ALTER TABLE trips
    ADD CONSTRAINT trips_region_code_check
    CHECK (
        region_code IN (
            'JEONBUK',
            'JEONJU',
            'GUNSAN',
            'IKSAN',
            'JEONGEUP',
            'NAMWON',
            'GIMJE',
            'WANJU',
            'JINAN',
            'MUJU',
            'JANGSU',
            'IMSIL',
            'SUNCHANG',
            'GOCHANG',
            'BUAN'
        )
    );
