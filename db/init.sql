-- Enable PostGIS
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Spots table (compatible with Exposed ORM)
CREATE TABLE IF NOT EXISTS spots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    region VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL,
    access_type VARCHAR(50) NOT NULL, -- shore, boat, kayak
    depth_min_m DOUBLE PRECISION,
    depth_max_m DOUBLE PRECISION,
    difficulty VARCHAR(20), -- beginner, intermediate, advanced, expert
    parking BOOLEAN DEFAULT false,
    parking_info TEXT,
    permits_required BOOLEAN DEFAULT false,
    permit_info TEXT,
    directions TEXT,
    hazards TEXT, -- Comma-separated for Exposed compatibility
    target_species TEXT, -- Comma-separated for Exposed compatibility
    best_months VARCHAR(50), -- Comma-separated for Exposed compatibility
    image_url TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Create PostGIS geography column for spatial queries
ALTER TABLE spots ADD COLUMN IF NOT EXISTS location GEOGRAPHY(POINT, 4326);

-- Update location from lat/lon (trigger on insert/update)
CREATE OR REPLACE FUNCTION update_spot_location()
RETURNS TRIGGER AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS spots_location_trigger ON spots;
CREATE TRIGGER spots_location_trigger
    BEFORE INSERT OR UPDATE ON spots
    FOR EACH ROW
    EXECUTE FUNCTION update_spot_location();

-- Spatial index for geo queries
CREATE INDEX IF NOT EXISTS spots_location_idx ON spots USING GIST (location);

-- Region and country indexes for filtering
CREATE INDEX IF NOT EXISTS spots_region_idx ON spots (region);
CREATE INDEX IF NOT EXISTS spots_country_idx ON spots (country);
CREATE INDEX IF NOT EXISTS spots_coords_idx ON spots (latitude, longitude);

-- Community reports table
CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    spot_id UUID REFERENCES spots(id) ON DELETE SET NULL,
    source VARCHAR(100) NOT NULL, -- reddit, spearboard, local
    source_url TEXT,
    report_date TIMESTAMP NOT NULL,
    visibility_m DOUBLE PRECISION,
    water_temp_c DOUBLE PRECISION,
    fish_sighted TEXT, -- Comma-separated
    conditions_notes TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS reports_spot_idx ON reports (spot_id);
CREATE INDEX IF NOT EXISTS reports_date_idx ON reports (report_date DESC);

-- Spot cache table for persisting prefetched data
-- This survives server restarts and can rebuild in-memory cache
CREATE TABLE IF NOT EXISTS spot_cache (
    spot_id VARCHAR(100) PRIMARY KEY,
    
    -- Tide data
    tide_state VARCHAR(20),
    tide_height_ft DOUBLE PRECISION,
    tide_next_time TIMESTAMP,
    tide_next_high VARCHAR(50),
    tide_next_low VARCHAR(50),
    tide_fetched_at TIMESTAMP,
    
    -- Weather/Swell data  
    swell_height_ft DOUBLE PRECISION,
    swell_period_sec DOUBLE PRECISION,
    swell_direction VARCHAR(10),
    wind_speed_kts DOUBLE PRECISION,
    wind_direction VARCHAR(10),
    weather_fetched_at TIMESTAMP,
    
    -- Satellite data
    visibility_m DOUBLE PRECISION,
    sst_celsius DOUBLE PRECISION,
    chlorophyll_mg_m3 DOUBLE PRECISION,
    satellite_date DATE,
    satellite_fetched_at TIMESTAMP,
    
    -- Metadata
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS spot_cache_updated_idx ON spot_cache (updated_at);

-- Function to find spots within radius using PostGIS
CREATE OR REPLACE FUNCTION find_spots_within_radius(
    search_lat DOUBLE PRECISION,
    search_lon DOUBLE PRECISION,
    radius_km DOUBLE PRECISION
)
RETURNS TABLE (
    spot_id UUID,
    spot_name VARCHAR,
    spot_description TEXT,
    spot_latitude DOUBLE PRECISION,
    spot_longitude DOUBLE PRECISION,
    distance_km DOUBLE PRECISION,
    spot_region VARCHAR,
    spot_country VARCHAR,
    spot_access_type VARCHAR,
    spot_depth_min_m DOUBLE PRECISION,
    spot_depth_max_m DOUBLE PRECISION,
    spot_difficulty VARCHAR,
    spot_target_species TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id,
        s.name,
        s.description,
        s.latitude,
        s.longitude,
        ST_Distance(
            s.location, 
            ST_SetSRID(ST_MakePoint(search_lon, search_lat), 4326)::geography
        ) / 1000.0 AS dist_km,
        s.region,
        s.country,
        s.access_type,
        s.depth_min_m,
        s.depth_max_m,
        s.difficulty,
        s.target_species
    FROM spots s
    WHERE ST_DWithin(
        s.location,
        ST_SetSRID(ST_MakePoint(search_lon, search_lat), 4326)::geography,
        radius_km * 1000
    )
    ORDER BY dist_km;
END;
$$ LANGUAGE plpgsql;

-- Function to calculate Haversine distance (fallback if PostGIS unavailable)
CREATE OR REPLACE FUNCTION haversine_distance(
    lat1 DOUBLE PRECISION, lon1 DOUBLE PRECISION,
    lat2 DOUBLE PRECISION, lon2 DOUBLE PRECISION
)
RETURNS DOUBLE PRECISION AS $$
DECLARE
    R DOUBLE PRECISION := 6371.0; -- Earth radius in km
    dLat DOUBLE PRECISION;
    dLon DOUBLE PRECISION;
    a DOUBLE PRECISION;
    c DOUBLE PRECISION;
BEGIN
    dLat := RADIANS(lat2 - lat1);
    dLon := RADIANS(lon2 - lon1);
    a := SIN(dLat/2) * SIN(dLat/2) + COS(RADIANS(lat1)) * COS(RADIANS(lat2)) * SIN(dLon/2) * SIN(dLon/2);
    c := 2 * ATAN2(SQRT(a), SQRT(1-a));
    RETURN R * c;
END;
$$ LANGUAGE plpgsql;

-- ================================================================
-- SPOT DATA - All 789 dive spots with corrected water coordinates
-- Generated from SpotDatabase.kt on Fri Jan 30 23:21:56 PST 2026
-- ================================================================

-- Generated SQL INSERT for 789 spots
-- Run this on Railway PostgreSQL

TRUNCATE TABLE spots CASCADE;

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark''s Cove', 'SHORE ENTRY - Premier North Shore spot with protected cove and lava formations. Summer only. Enter from beach, reef structure 50-100m offshore.', 21.6502, -158.0678, 'Oahu', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Three Tables', 'Three flat reef sections at low tide. Excellent visibility.', 21.6464, -158.0681, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Waimea Bay', 'World-famous bay, calm in summer with excellent reef edges.', 21.6430, -158.0667, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sunset Point', 'Rocky reef, less crowded. Watch for currents.', 21.6686, -158.0492, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Haleiwa Harbor Reef', 'Near harbor entrance. Good for papio and ulua.', 21.5966, -158.1056, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Puaena Point', 'Local spot near harbor. Respect regulars.', 21.598, -158.10916, 'Oahu', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chun''s Reef', 'Named for waterman Chun. Good structure for reef fish.', 21.6208, -158.0900, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Laniakea (Turtle Beach)', 'Turtle area - be careful. Adjacent reef good for fish.', 21.6208, -158.0900, 'Oahu', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Papailoa Beach', 'Less crowded North Shore spot with good reef.', 21.6108, -158.1000, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kawela Bay', 'Protected bay with calm water. Good for beginners.', 21.701076, -158.011442, 'Oahu', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Electric Beach (Kahe Point)', 'SHORE ENTRY - Power plant warm water outflow attracts marine life. Easy beach entry, reef structure begins 20m offshore. Dolphins, turtles common.', 21.3525, -158.1346, 'Oahu', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makaha Beach', 'Legendary surf spot with excellent reef. Watch currents.', 21.4731, -158.2196, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makaha Caverns', 'Underwater lava tubes with excellent structure.', 21.4750, -158.2200, 'Oahu', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makua Beach', 'Remote west side with clear water. Military area nearby.', 21.5272, -158.2366, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yokohama Bay (Keawaula)', 'Remote beach at end of road. Pristine when calm.', 21.5533, -158.2535, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Maili Point', 'Good reef structure, local spot.', 21.4200, -158.1860, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Nanakuli Beach', 'West side community beach with reef.', 21.3900, -158.1560, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pokai Bay', 'Protected bay, good for beginners.', 21.4350, -158.1960, 'Oahu', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaena Point (South)', 'Remote area, excellent when accessible.', 21.566806, -158.267565, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Waianae Boat Harbor', 'Harbor reef and outside break.', 21.4450, -158.1900, 'Oahu', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hanauma Bay', 'Marine preserve - no spearing inside. Fish outside boundary.', 21.2681, -157.6950, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('China Walls', 'Cliff diving and spearfishing spot. Strong currents.', 21.2592, -157.7100, 'Oahu', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Portlock Point', 'Premium spot with deep water access. Sharks present.', 21.2592, -157.7050, 'Oahu', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Spitting Caves', 'Dramatic cliffs, deep water. Advanced only.', 21.2542, -157.7150, 'Oahu', 'USA', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kahala Reef', 'Offshore reef with good structure.', 21.2700, -157.7700, 'Oahu', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ala Moana Bowls', 'Near channel, watch for boat traffic.', 21.2842, -157.8500, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kewalo Basin', 'Harbor area with fish aggregation.', 21.2892, -157.8600, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Diamond Head Cliffs', 'Deep water off the cliffs. Boat access better.', 21.2550, -157.8050, 'Oahu', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Black Point', 'Rocky coast with good structure.', 21.2592, -157.7900, 'Oahu', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Waikiki Reef', 'Offshore reef, boat access. Tourist area.', 21.2700, -157.8300, 'Oahu', 'USA', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makapuu Tidepools', 'Tidepools and reef near lighthouse.', 21.3108, -157.6490, 'Oahu', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Waimanalo Bay', 'Long beach with offshore reef.', 21.3350, -157.6940, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bellows Beach', 'Military beach, open weekends. Clear water.', 21.355, -157.703184, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lanikai Beach', 'Pristine beach with Mokulua Islands offshore.', 21.395417, -157.714, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kailua Bay', 'Popular bay with good reef structure.', 21.4000, -157.7290, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Flat Island (Popoia)', 'Offshore island with surrounding reef.', 21.4100, -157.7400, 'Oahu', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mokulua Islands', 'Bird sanctuary, fish the surrounding waters.', 21.3850, -157.7000, 'Oahu', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaneohe Bay', 'Large bay with many reef patches. Boat recommended.', 21.4500, -157.8000, 'Oahu', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coconut Island (Moku o Loe)', 'Hawaii Institute of Marine Biology. Fish around, not on.', 21.4350, -157.7900, 'Oahu', 'USA', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chinaman''s Hat (Mokoli''i)', 'Iconic island with surrounding reef.', 21.5050, -157.8400, 'Oahu', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kahana Bay', 'Protected bay with calm water.', 21.560417, -157.874, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Punaluu', 'Small beach with reef structure.', 21.58441, -157.884, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hauula Beach', 'Local area with reef.', 21.612646, -157.905776, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Laie Point', 'Rocky point with current and fish.', 21.645, -157.91414, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Malaekahana Bay', 'State park with reef and small island.', 21.6650, -157.9340, 'Oahu', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goat Island (Moku''auia)', 'Small island accessible at low tide.', 21.6700, -157.9290, 'Oahu', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kahuku Point', 'Remote point, difficult access.', 21.714518, -157.974, 'Oahu', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Two Step (Honaunau)', 'SHORE ENTRY - Iconic two-step lava entry into deep water. Wall dive starts immediately at shoreline, drops to 60ft+. Prime mu, kumu, uhu territory.', 19.42117, -155.915061, 'Bigisland', 'USA', 'shore', 60, 60);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kealakekua Bay', 'Captain Cook monument. Marine preserve - check boundaries.', 19.4800, -155.9300, 'Bigisland', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Puako', 'Long reef with multiple access points.', 19.975813, -155.846, 'Bigisland', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Keahole Point', 'Near airport with NELHA pipes. Deep water close.', 19.7300, -156.0610, 'Bigisland', 'USA', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Honokohau Harbor', 'Harbor reef and outside structure.', 19.67, -156.033663, 'Bigisland', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaloko Fish Pond', 'Historic fish pond with adjacent reef.', 19.67617, -156.035068, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pine Trees', 'Local surf spot with reef.', 19.7200, -156.0560, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Wawaloli Beach', 'OTEC Beach with deep water pipe.', 19.7150, -156.0510, 'Bigisland', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kua Bay (Manini''owali)', 'White sand beach with clear water.', 19.807216, -156.016, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kikaua Point', 'Point with good current and fish.', 19.8100, -156.0110, 'Bigisland', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahaiula Beach', 'Remote Kohala coast beach.', 19.8350, -155.9910, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makalawena Beach', 'Remote hike-in beach with pristine water.', 19.83941, -155.986, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kiholo Bay', 'Large bay with good reef.', 19.8550, -155.9260, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('A-Bay (Anaehoomalu)', 'Waikoloa resort beach with protected bay.', 19.914999, -155.891327, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Beach 69 (Waialea)', 'Popular beach with offshore reef.', 19.973455, -155.850638, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hapuna Beach', 'Large white sand beach, some reef on sides.', 19.977354, -155.834135, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Spencer Beach', 'Protected beach near harbor.', 20.019898, -155.82643, 'Bigisland', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kawaihae Harbor', 'Working harbor with fish aggregation.', 20.0350, -155.8300, 'Bigisland', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahukona', 'Old sugar port with excellent diving.', 20.175482, -155.901, 'Bigisland', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lapakahi', 'Historic park with marine preserve.', 20.174999, -155.906352, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Keokea Beach', 'Remote north Kohala beach.', 20.209995, -155.913189, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Richardson Beach', 'Black sand with clear water.', 19.7350, -155.0190, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Carlsmith Beach', 'Protected area with brackish pools.', 19.7400, -155.0090, 'Bigisland', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Honolii Beach', 'Surf spot with adjacent reef.', 19.768194, -155.085606, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kolekole Beach', 'Dramatic gulch with river mouth.', 19.88941, -155.119, 'Bigisland', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Laupahoehoe Point', 'Historic point with reef.', 19.990417, -155.234, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Point (Ka Lae)', 'Southernmost point in USA. Strong currents, big fish.', 18.9142, -155.6850, 'Bigisland', 'USA', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Green Sand Beach (Papakolea)', 'Hike-in beach with unique sand.', 18.9342, -155.6400, 'Bigisland', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Punaluu Black Sand', 'Turtle beach with adjacent reef.', 19.1292, -155.5050, 'Bigisland', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Waianae Artificial Reef', 'VERIFIED GPS - Multiple reef structures including Mahi wreck at 90ft, Navy barge, LCUs, Z-modules. Depths 38-127ft.', 21.4132, -158.1956, 'Oahu', 'USA', 'boat', 90, 90);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Maunalua Bay Artificial Reef', 'VERIFIED GPS - Multiple structures: CB Barge, Navy LCU, Keehi Barge. Depths 52-87ft. Hawaii Kai area.', 21.2498, -157.7640, 'Oahu', 'USA', 'boat', 85, 85);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kualoa Artificial Reef', 'VERIFIED GPS - Large reef complex: Z-modules, Small Barge at 85ft. Windward Oahu. Depths 85-211ft.', 21.5525, -157.8255, 'Oahu', 'USA', 'boat', 85, 85);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ewa Deepwater Artificial Reef', 'VERIFIED GPS - Deep artificial reef complex. Depths 322-537ft. Advanced/technical only.', 21.2803, -158.0228, 'Oahu', 'USA', 'boat', 330, 330);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Keawakapu Artificial Reef', 'VERIFIED GPS - Artificial reef structure off South Maui. Depths 71-180ft.', 20.7000, -156.4566, 'Maui', 'USA', 'boat', 120, 120);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Point FAD (Buoy A)', 'VERIFIED GPS - Fish Aggregating Device 8mi offshore of South Point. 700 fathoms. Ahi, aku, mahimahi, ono.', 18.9558, -155.5567, 'Bigisland', 'USA', 'boat', 4200, 4200);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Milolii FAD (Buoy B)', 'VERIFIED GPS - Fish Aggregating Device 2.3mi offshore of Milolii. 850 fathoms. Ahi, aku, mahimahi.', 19.1983, -155.9483, 'Bigisland', 'USA', 'boat', 5100, 5100);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kailua-Kona FAD (Buoy F)', 'VERIFIED GPS - Fish Aggregating Device 10mi offshore of Kailua Bay. 1592 fathoms. Ahi, aku, mahimahi, ono.', 19.5067, -156.1567, 'Bigisland', 'USA', 'boat', 9550, 9550);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kahaluu FAD (Buoy VV)', 'VERIFIED GPS - Fish Aggregating Device 4mi offshore of Kahaluu. 600 fathoms. Ahi, aku, mahimahi.', 19.5850, -156.0317, 'Bigisland', 'USA', 'boat', 3600, 3600);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Puako FAD (Buoy XX)', 'VERIFIED GPS - Fish Aggregating Device 12mi offshore of Puako. 641 fathoms. Ahi, aku, mahimahi, ono.', 20.0367, -156.1033, 'Bigisland', 'USA', 'boat', 3850, 3850);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Molokini Crater', 'VERIFIED GPS - Submerged volcanic crater 2.5mi off Maui. Multiple dive sites: Back Side (100ft wall), Shark Condos (130ft caves), Edge of the World. Exceptional clarity.', 20.6335, -156.4917, 'Maui', 'USA', 'boat', 100, 100);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Honolua Bay', 'Marine preserve - some areas no-take. Check boundaries.', 21.0150, -156.6410, 'Maui', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kapalua Bay', 'Protected bay with good snorkeling.', 21.0000, -156.6660, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Napili Bay', 'Calm bay with reef.', 20.989999, -156.676434, 'Maui', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Black Rock (Puu Kekaa)', 'Famous cliff jump spot with reef.', 20.9250, -156.6960, 'Maui', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Olowalu', 'Mile marker 14 with extensive reef.', 20.805482, -156.621, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ukumehame', 'Less crowded reef system.', 20.7950, -156.5910, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Maalaea Harbor', 'Harbor reef and offshore spots.', 20.7900, -156.5100, 'Maui', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Five Caves (Five Graves)', 'Dramatic underwater caves. Advanced only.', 20.6692, -156.4450, 'Maui', 'USA', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ahihi-Kinau Reserve', 'Marine preserve - special regulations.', 20.616554, -156.443169, 'Maui', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Perouse Bay', 'Remote lava field with good diving.', 20.5942, -156.4200, 'Maui', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kamaole Beach Parks', 'Three beach parks with reef.', 20.7192, -156.4500, 'Maui', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Makena Landing', 'Boat launch with adjacent reef.', 20.6542, -156.4450, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Big Beach (Oneloa)', 'Large beach with some reef on sides.', 20.6342, -156.4550, 'Maui', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hookipa Beach', 'Windsurfing spot with reef. Strong currents.', 20.9358, -156.3600, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Baldwin Beach', 'Long beach near Paia.', 20.9158, -156.3850, 'Maui', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hana Bay', 'Remote east Maui with good diving.', 20.759518, -155.984, 'Maui', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hamoa Beach', 'Beautiful beach past Hana.', 20.7150, -155.9840, 'Maui', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Keanae Peninsula', 'Rocky peninsula with tidepools.', 20.8608, -156.1400, 'Maui', 'USA', 'shore', 6, 6);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kahului Harbor', 'Working harbor, fish around structures.', 20.9000, -156.4700, 'Maui', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sombrero Reef', 'Iconic lighthouse reef with excellent structure.', 24.6261, -81.1108, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Looe Key', 'Named for HMS Looe wreck. Outstanding reef.', 24.5456, -81.4075, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coffins Patch', 'Large reef complex, multiple dive sites.', 24.6833, -81.0500, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('American Shoal', 'Deep reef with bigger fish.', 24.5161, -81.5231, 'Keys', 'Keys', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bahia Honda Bridge', 'Old bridge structure attracts fish.', 24.6553, -81.2842, 'Keys', 'Keys', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Newfound Harbor Keys', 'Protected area with diverse reef.', 24.6100, -81.3900, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Marquesas Keys', 'Remote atoll west of Key West.', 24.5500, -82.1000, 'Keys', 'Keys', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dry Tortugas - North', 'North side. Remote national park.', 24.6433, -82.8781, 'Keys', 'Keys', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dry Tortugas - South', 'South side. Pristine waters.', 24.6133, -82.8781, 'Keys', 'Keys', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dry Tortugas - East', 'East side. Loggerhead Key.', 24.6283, -82.8531, 'Keys', 'Keys', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dry Tortugas - West', 'West side. Open Gulf.', 24.6283, -82.8931, 'Keys', 'Keys', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tennessee Reef', 'Large reef system off Islamorada.', 24.7619, -80.7489, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Alligator Reef', 'Lighthouse reef, excellent diving.', 24.8481, -80.6186, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Davis Reef', 'Popular reef off Upper Keys.', 24.9244, -80.5028, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Conch Reef', 'Healthy reef ecosystem.', 24.9533, -80.4564, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Molasses Reef', 'Very popular snorkel/dive reef.', 25.0092, -80.3739, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('French Reef', 'Multiple dive sites and caves.', 25.0344, -80.3508, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Carysfort Reef', 'Northern Keys lighthouse reef.', 25.2228, -80.2114, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pickles Reef', 'Named for pickle barrel cargo from wreck.', 24.9858, -80.4131, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('USCGC Duane Wreck', '327ft Coast Guard cutter in 120ft.', 24.9867, -80.3822, 'Keys', 'Keys', 'boat', 36, 36);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('USCGC Bibb Wreck', 'Coast Guard cutter, sister to Duane.', 24.9875, -80.3819, 'Keys', 'Keys', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('USS Spiegel Grove', '510ft landing ship, largest artificial reef.', 25.0628, -80.3086, 'Keys', 'Keys', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Thunderbolt Wreck', 'Research vessel in 115ft of water.', 24.6564, -81.0333, 'Keys', 'Keys', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('USNS Vandenberg', '523ft ship, second largest artificial reef.', 24.4569, -81.8019, 'Keys', 'Keys', 'boat', 43, 43);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Western Sambo Reef', 'Near Key West with good coral.', 24.4792, -81.7147, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Eastern Sambo Reef', 'Healthy reef system.', 24.4897, -81.6661, 'Keys', 'Keys', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sand Key', 'Lighthouse reef off Key West.', 24.4528, -81.8775, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rock Key', 'Rocky outcrop with fish aggregation.', 24.4517, -81.8578, 'Keys', 'Keys', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Wall at Fresh Creek', 'Third largest barrier reef drops into Tongue of Ocean.', 24.7167, -77.7667, 'Andros', 'Andros', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Blue Hole (Andros)', 'Second deepest blue hole in Bahamas.', 24.4450, -77.9000, 'Andros', 'Andros', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('North Andros Barrier Reef', 'Shallower section, excellent hogfish.', 25.049997, -77.971189, 'Andros', 'Andros', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stafford Creek Wall', 'Less crowded section with big grouper.', 24.7833, -77.8333, 'Andros', 'Andros', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Captain Bill''s Blue Hole', 'Famous inland blue hole.', 24.1617, -77.7500, 'Andros', 'Andros', 'shore', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ocean Hole (North Andros)', 'Deep ocean blue hole near Nicholl''s Town.', 25.161292, -78.00237, 'Andros', 'Andros', 'boat', 50, 50);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mangrove Cay Reef', 'Central Andros, less pressure.', 24.4000, -77.7833, 'Andros', 'Andros', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Bight', 'Remote southern area.', 23.8550, -77.6500, 'Andros', 'Andros', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Thunderball Grotto', 'Famous James Bond cave near Staniel Cay.', 24.1708, -76.4339, 'Exuma', 'Exuma', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Warderick Wells', 'Exuma Park HQ with pristine reef.', 24.3833, -76.6167, 'Exuma', 'Exuma', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shroud Cay', 'Northern park boundary with creek system.', 24.6000, -76.5667, 'Exuma', 'Exuma', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stocking Island Blue Hole', 'Popular blue hole near Georgetown.', 23.5333, -75.7717, 'Exuma', 'Exuma', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Highborne Cay', 'Northern Exumas near Nassau.', 24.7167, -76.8167, 'Exuma', 'Exuma', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Compass Cay', 'Famous for nurse sharks.', 24.2667, -76.5000, 'Exuma', 'Exuma', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Big Major Cay (Pig Beach)', 'Swimming pigs with adjacent reef.', 24.1833, -76.4500, 'Exuma', 'Exuma', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Norman''s Cay Drug Plane', 'Famous C-46 wreck in shallow water.', 24.5833, -76.8167, 'Exuma', 'Exuma', 'boat', 5, 5);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Exuma Wall', 'Wall dive off main island.', 23.5000, -75.8333, 'Exuma', 'Exuma', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Little Exuma Reef', 'Southern tip of chain.', 23.3167, -75.8500, 'Exuma', 'Exuma', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Leaf Cay (Iguana Island)', 'Iguanas on land, good reef.', 24.2000, -76.4600, 'Exuma', 'Exuma', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pipe Creek', 'Shallow area with patch reefs.', 24.2500, -76.5500, 'Exuma', 'Exuma', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Current Cut', 'World''s fastest drift dive at 9 knots.', 25.3833, -76.7833, 'Eleuthera', 'Eleuthera', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Devil''s Backbone', 'Shipwreck graveyard with reef.', 25.4167, -76.7333, 'Eleuthera', 'Eleuthera', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Train Wreck', 'Civil War era locomotive on reef.', 25.4200, -76.7300, 'Eleuthera', 'Eleuthera', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Split Reef', 'Large coral head at 45ft with swim-throughs.', 25.5000, -76.7500, 'Eleuthera', 'Eleuthera', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('High Head', 'Steep coral head 10-50ft.', 25.4833, -76.7667, 'Eleuthera', 'Eleuthera', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Black Shoals', 'Reef heads with morays and turtles.', 25.4500, -76.7833, 'Eleuthera', 'Eleuthera', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Notch', 'Wall dive known for reef sharks.', 25.5167, -76.7333, 'Eleuthera', 'Eleuthera', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('North Eleuthera Wall', 'Dramatic wall, minimal pressure.', 25.5500, -76.6500, 'Eleuthera', 'Eleuthera', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Governor''s Harbour Reef', 'Central Eleuthera access.', 25.2000, -76.2500, 'Eleuthera', 'Eleuthera', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rock Sound Blue Hole', 'Massive inland blue hole.', 24.8833, -76.188081, 'Eleuthera', 'Eleuthera', 'shore', 80, 80);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cape Eleuthera', 'Southern tip with wall.', 24.7667, -76.3333, 'Eleuthera', 'Eleuthera', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('First Basin Wall', '100-200ft drop-off.', 24.2667, -75.4167, 'Cat', 'Cat', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cat Island Blue Hole', '80-100ft circular depression.', 24.2000, -75.4500, 'Cat', 'Cat', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('White Hole Reef', 'Unique limestone formations.', 24.2333, -75.4333, 'Cat', 'Cat', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Tunnels', 'Shore dive with crevices and canyons.', 24.3167, -75.4000, 'Cat', 'Cat', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Third Basin Reef', 'Vertical wall with black coral.', 24.1833, -75.4717, 'Cat', 'Cat', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dry Heads', 'One of finest shallow Bahamian reefs.', 24.3500, -75.3667, 'Cat', 'Cat', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hawk''s Nest Reef', 'Resort area southern Cat Island.', 24.0833, -75.5167, 'Cat', 'Cat', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fernandez Bay', 'Beautiful bay with reef.', 24.119402, -75.475, 'Cat', 'Cat', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cape Santa Maria Reef', 'Northern Long Island pristine reef.', 23.6833, -75.2833, 'Long', 'Long', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dean''s Blue Hole', 'World''s second deepest at 663ft. Freediving mecca.', 23.1083, -74.9917, 'Long', 'Long', 'shore', 200, 200);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Clarence Town Wall', '8 miles of wall from Flying Fish Marina.', 23.110813, -74.9833, 'Long', 'Long', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stella Maris Reef', 'Central Long Island with shark dives.', 23.576889, -75.277818, 'Long', 'Long', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Salt Pond Reef', 'Good reef near main settlement.', 23.3000, -75.0500, 'Long', 'Long', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hamilton''s Cave Reef', 'Near famous cave system.', 23.4500, -75.1500, 'Long', 'Long', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Grand Canyon (Rum Cay)', '60ft coral wall nearly to surface.', 23.6550, -74.8333, 'Rum', 'Rum', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dynamite Wall', 'Deep tunnels with staghorn coral.', 23.645095, -74.85, 'Rum', 'Rum', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pinder Reef', 'Predictable sharks and rays.', 23.7000, -74.8833, 'Rum', 'Rum', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hyperspace', 'Mushroom coral heads with tunnels.', 23.6333, -74.8167, 'Rum', 'Rum', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Seagarden', 'Shallow site with prolific lobster.', 23.6167, -74.8000, 'Rum', 'Rum', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('HMS Conqueror Wreck', 'Historic British shipwreck.', 23.6250, -74.8250, 'Rum', 'Rum', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Riding Rock Wall', 'Columbus landfall island with great wall.', 24.0500, -74.5333, 'Salvador', 'Salvador', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Snapshot Reef', 'One of healthiest reefs in Bahamas.', 24.0833, -74.5000, 'Salvador', 'Salvador', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Frascate Wreck', '1902 steamship on reef.', 24.0333, -74.5500, 'Salvador', 'Salvador', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Telephone Pole', 'Steep wall with coral pillars.', 24.068194, -74.516501, 'Salvador', 'Salvador', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Devil''s Claw', 'Dramatic reef formation.', 24.0750, -74.4900, 'Salvador', 'Salvador', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('West Bay (Conception)', 'Crescent cove, turtle sanctuary.', 23.8333, -75.1333, 'Conception', 'Conception', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Hampton Reef', 'North side with staghorn coral.', 23.8667, -75.1167, 'Conception', 'Conception', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Conception Creek Drift', 'Mangrove creek drift dive.', 23.8400, -75.1400, 'Conception', 'Conception', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Crooked Island Wall', 'Untouched reef with 200 residents.', 22.78435, -74.196038, 'Crooked', 'Crooked', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('French Wells', 'Shallow reef with large undercuts.', 22.677109, -74.2, 'Crooked', 'Crooked', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Acklins Atoll Rim', '140-mile atoll with vast flats.', 22.5000, -74.0000, 'Acklins', 'Acklins', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Jamaica Cay', 'Remote cay with pristine reef.', 22.4000, -74.1000, 'Acklins', 'Acklins', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chub Cay Reef', 'PADI 5-star resort reef.', 25.4167, -77.9050, 'Berry', 'Berry', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Harbour Cay Reef', 'Best hurricane hole, good spearing.', 25.7383, -77.8333, 'Berry', 'Berry', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Berry Islands TOTO Edge', 'Where shelf meets 6,600ft trench.', 25.2833, -77.8667, 'Berry', 'Berry', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Little Whale Cay', 'Southern Berrys near deep water.', 25.3000, -77.8500, 'Berry', 'Berry', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bird Cay', 'Private island with surrounding reef.', 25.3167, -77.8833, 'Berry', 'Berry', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Frazer''s Hog Cay', 'Good reef structure.', 25.5000, -77.8000, 'Berry', 'Berry', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hoffman''s Cay Blue Hole', 'Large blue hole with cave system.', 25.6500, -77.7550, 'Berry', 'Berry', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('North Bimini Reef', 'Gulf Stream waters, 50 miles from Miami.', 25.75, -79.237995, 'Bimini', 'Bimini', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bimini Road', 'Mysterious underwater rock formation.', 25.7667, -79.2833, 'Bimini', 'Bimini', 'boat', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bull Run', 'Bull shark encounters.', 25.7833, -79.3000, 'Bimini', 'Bimini', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Victory Reef', 'Shallow reef with excellent hogfish.', 25.7333, -79.2667, 'Bimini', 'Bimini', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bimini Flats', 'Famous bonefish flats.', 25.6833, -79.2333, 'Bimini', 'Bimini', 'boat', 5, 5);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Nodules', 'Deep structure off Bimini.', 25.7000, -79.3500, 'Bimini', 'Bimini', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Three Sisters', 'Rock formations with fish.', 25.7200, -79.3200, 'Bimini', 'Bimini', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fowl Cay', 'Protected reef near Marsh Harbour.', 26.5833, -77.0667, 'Abaco', 'Abaco', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pelican Cays Land & Sea Park', 'National park with buffer zones.', 26.35651, -77.021928, 'Abaco', 'Abaco', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Grand Cay', 'Remote northern tip, monster grouper.', 27.2167, -78.3167, 'Abaco', 'Abaco', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hole in the Wall', 'Southern tip lighthouse, remote.', 25.8500, -77.1833, 'Abaco', 'Abaco', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Man-O-War Cay', 'Historic boat-building with reef.', 26.5833, -76.9833, 'Abaco', 'Abaco', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Green Turtle Cay Reef', 'Charming settlement with reef.', 26.7667, -77.3333, 'Abaco', 'Abaco', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Walker''s Cay', 'Northernmost Bahamas.', 27.2667, -78.4000, 'Abaco', 'Abaco', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Whale Cay', 'Near Great Guana Cay.', 26.6833, -77.2000, 'Abaco', 'Abaco', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fish Cay', 'South of Pelican Cays.', 26.3000, -77.0500, 'Abaco', 'Abaco', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sandy Point', 'Southwestern Abaco.', 26.0167, -77.3833, 'Abaco', 'Abaco', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark Wall (Stuart Cove''s)', 'Famous shark dive.', 25.0167, -77.5550, 'Nassau', 'Nassau', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Clifton Wall', 'Western New Providence wall.', 25.0000, -77.5333, 'Nassau', 'Nassau', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tongue of the Ocean', '6,000ft drop-off accessible from Nassau.', 24.2500, -77.5000, 'Nassau', 'Nassau', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rose Island Reef', 'Popular day trip from Nassau.', 25.1000, -77.3500, 'Nassau', 'Nassau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Southwest Reef', 'Less visited south side.', 24.9833, -77.4000, 'Nassau', 'Nassau', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goulding Cay', 'Near Clifton Heritage Park.', 25.0333, -77.5667, 'Nassau', 'Nassau', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Athol Island - North', 'North coast. Near Paradise Island.', 25.1150, -77.2833, 'Nassau', 'Nassau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Athol Island - South', 'South coast. Nassau harbor.', 25.0850, -77.2833, 'Nassau', 'Nassau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Athol Island - East', 'East coast. Open Atlantic.', 25.104518, -77.2683, 'Nassau', 'Nassau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Athol Island - West', 'West coast. Facing Nassau.', 25.1000, -77.2983, 'Nassau', 'Nassau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('UNEXSO Reef (Freeport)', 'Underwater Explorers Society.', 26.50651, -78.638614, 'Grand', 'Grand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('West End Wall', 'Western tip of Grand Bahama.', 26.6833, -79.0000, 'Grand', 'Grand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Peterson Cay National Park', 'Smallest national park.', 26.4333, -78.5667, 'Grand', 'Grand', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gold Rock Beach Reef', 'Near Lucayan National Park.', 26.5500, -78.0000, 'Grand', 'Grand', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sweeting''s Cay', 'Eastern Grand Bahama.', 26.5833, -77.8333, 'Grand', 'Grand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark Alley', 'Known shark encounter site.', 26.576101, -78.7, 'Grand', 'Grand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tetamanu Pass (South)', '200m wide, wall of sharks.', -16.6872, -145.2511, 'Fakarava', 'Fakarava', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Garuae Pass (North)', 'Largest pass in FP - 1.6km wide.', -16.0556, -145.6556, 'Fakarava', 'Fakarava', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark Grotto', 'Grey reef sharks rest during day.', -16.6900, -145.2500, 'Fakarava', 'Fakarava', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ali Baba', 'Coral garden inside south pass.', -16.6850, -145.2550, 'Fakarava', 'Fakarava', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pufana', 'Outside south pass with pelagics.', -16.6900, -145.2400, 'Fakarava', 'Fakarava', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tiputa Pass', 'Premier drift dive with dolphins and hammerheads.', -14.9683, -147.6383, 'Rangiroa', 'Rangiroa', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sharks Cavern', '115ft site where sharks investigate.', -14.9700, -147.6350, 'Rangiroa', 'Rangiroa', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Canyons', 'Natural canyons mid-pass.', -14.9650, -147.6300, 'Rangiroa', 'Rangiroa', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Avatoru Pass', 'Two channels, eastern for beginners.', -14.9500, -147.7000, 'Rangiroa', 'Rangiroa', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Blue Lagoon', 'Inner lagoon with pristine water.', -15.0000, -147.5500, 'Rangiroa', 'Rangiroa', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Reef Island (Les Sables Roses)', 'Pink sand island with reef.', -15.0500, -147.5000, 'Rangiroa', 'Rangiroa', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Opunohu Pass', 'Deep pass with Jardin des Roses at 40m.', -17.4833, -149.8500, 'Moorea', 'Moorea', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tiki (Moorea)', 'Northwest tip with rapid currents.', -17.4667, -149.9333, 'Moorea', 'Moorea', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Vaiare', 'Near ferry docks with lemon sharks.', -17.5167, -149.7667, 'Moorea', 'Moorea', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taotoi', 'Beginner site with morays and sharks.', -17.499999, -149.822653, 'Moorea', 'Moorea', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stingray World', 'Shallow lagoon with friendly rays.', -17.480409, -149.83, 'Moorea', 'Moorea', 'boat', 5, 5);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bora Bora South Lagoon', 'Shallow lagoon hunting.', -16.5333, -151.7333, 'Bora', 'Bora', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bora Bora Outer Reef', 'Outside for pelagics.', -16.4833, -151.7833, 'Bora', 'Bora', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tapu', 'Manta cleaning station.', -16.4500, -151.7500, 'Bora', 'Bora', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Muri Muri', 'North pass with sharks.', -16.4600, -151.7600, 'Bora', 'Bora', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anau', 'Manta ray site east side.', -16.5000, -151.7050, 'Bora', 'Bora', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tuheiava Pass', 'Only pass into Tikehau. Mantas year-round.', -15.0000, -148.2333, 'Tikehau', 'Tikehau', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark Pit', 'Shark aggregation site.', -15.0100, -148.2400, 'Tikehau', 'Tikehau', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Manta Point (Tikehau)', 'Manta cleaning station.', -14.9900, -148.2200, 'Tikehau', 'Tikehau', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tairapa Pass', 'Historic pearl farming atoll.', -14.4333, -146.0717, 'Manihi', 'Manihi', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Manihi Drop-off', 'Dramatic wall outside pass.', -14.4400, -146.0550, 'Manihi', 'Manihi', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Aquarium', 'Shallow protected area.', -17.5333, -149.5717, 'Tahiti', 'French Polynesia', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Papeete Pass', 'Channel with current.', -17.5200, -149.5500, 'Tahiti', 'French Polynesia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Teahupoo Outer Reef', 'Famous surf spot, deep water.', -17.86441, -149.25, 'Tahiti', 'French Polynesia', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tavolara - North', 'North coast. Marine protected area.', 40.9133, 9.7100, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tavolara - South', 'South coast. Calmer waters.', 40.8833, 9.7100, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tavolara - East', 'East coast. Open Tyrrhenian.', 40.8983, 9.7250, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tavolara - West', 'West coast. Facing Sardinia.', 40.8983, 9.6750, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Maddalena Archipelago', 'National park with stunning water.', 41.211695, 9.4167, 'Sardinia', 'Sardinia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Capo Caccia', 'Dramatic cliffs near Alghero.', 40.5600, 8.1600, 'Sardinia', 'Sardinia', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Neptune''s Grotto Area', 'Caves and walls.', 40.5650, 8.1550, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Costa Smeralda', 'Luxury coast with good diving.', 41.068021, 9.536963, 'Sardinia', 'Sardinia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Carloforte (San Pietro)', 'Island off southwest coast.', 39.160189, 8.313141, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Villasimius MPA', 'Marine protected area, check zones.', 39.1283, 9.5333, 'Sardinia', 'Sardinia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gulf of Orosei', 'Stunning cliffs and caves.', 40.1667, 9.6833, 'Sardinia', 'Sardinia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ustica - North', 'North coast. Famous MPA.', 38.7367, 13.1833, 'Sicily', 'Sicily', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ustica - South', 'South coast. Facing Sicily.', 38.6867, 13.1833, 'Sicily', 'Sicily', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ustica - East', 'East coast. Open Tyrrhenian.', 38.7217, 13.1983, 'Sicily', 'Sicily', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ustica - West', 'West coast. Sunset side.', 38.7217, 13.1683, 'Sicily', 'Sicily', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Favignana (Egadi)', 'Tuna fishing heritage, clear water.', 37.9383, 12.3333, 'Sicily', 'Sicily', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Aeolian Islands', 'Volcanic island chain.', 38.5667, 14.9500, 'Sicily', 'Sicily', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taormina Coast', 'Below ancient theater.', 37.8450, 15.2833, 'Sicily', 'Sicily', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Capo Passero', 'Southernmost Sicily.', 36.678198, 15.139662, 'Sicily', 'Sicily', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Portofino MPA', 'Famous protected area.', 44.3000, 9.2117, 'Italy', 'Italy', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elba Island - North', 'North coast. Tuscan archipelago.', 42.817117, 10.2667, 'Italy', 'Italy', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elba Island - South', 'South coast. Calmer waters.', 42.7317, 10.2667, 'Italy', 'Italy', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elba Island - East', 'East coast. Facing mainland.', 42.7667, 10.4217, 'Italy', 'Italy', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elba Island - West', 'West coast. Open Tyrrhenian.', 42.7667, 10.0617, 'Italy', 'Italy', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ponza - North', 'North coast. Pontine Islands.', 40.9150, 12.9667, 'Italy', 'Italy', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ponza - South', 'South coast. Excellent vis.', 40.8850, 12.9667, 'Italy', 'Italy', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ponza - East', 'East coast. Facing mainland.', 40.9000, 12.9817, 'Italy', 'Italy', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ponza - West', 'West coast. Open Tyrrhenian.', 40.9000, 12.9317, 'Italy', 'Italy', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Amalfi Deep', 'Drop-offs near Li Galli.', 40.5833, 14.4283, 'Italy', 'Italy', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lavezzi Islands', 'Natural reserve, pristine water.', 41.3333, 9.2500, 'Corsica', 'Corsica', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Scandola Reserve', 'UNESCO site, limited access.', 42.3667, 8.5450, 'Corsica', 'Corsica', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cap Corse', 'Northern tip with good structure.', 42.9667, 9.3450, 'Corsica', 'Corsica', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ajaccio Bay', 'Near capital with accessible diving.', 41.9167, 8.7333, 'Corsica', 'Corsica', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Riou Archipelago', 'Islands off Marseille.', 43.1833, 5.3833, 'France', 'France', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Calanques National Park', 'Stunning limestone inlets.', 43.2000, 5.4500, 'France', 'France', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hyeres Islands', 'Port-Cros and Porquerolles.', 43.0167, 6.2167, 'France', 'France', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Villefranche-sur-Mer', 'Deep bay near Nice.', 43.7000, 7.3167, 'France', 'France', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cap d''Antibes', 'Rocky coast with good diving.', 43.549999, 7.148219, 'France', 'France', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Medes Islands', 'Famous marine reserve.', 42.0500, 3.2167, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tossa de Mar', 'Castle and underwater caves.', 41.7117, 2.9333, 'Spain', 'Spain', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cap de Creus (Cadaques)', 'Easternmost point of Iberian peninsula.', 42.338305, 3.2833, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dragonera - North', 'North coast. Protected island.', 39.6033, 2.3167, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dragonera - South', 'South coast. Calmer side.', 39.5633, 2.3167, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dragonera - East', 'East coast. Facing Mallorca.', 39.5883, 2.3417, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dragonera - West', 'West coast. Open Mediterranean.', 39.5883, 2.3017, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cabrera Archipelago', 'National park south of Mallorca.', 39.15, 2.944175, 'Spain', 'Spain', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('North Menorca Coast', 'Remote coves and reefs.', 40.06441, 3.95, 'Spain', 'Spain', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Es Vedra (Ibiza)', 'Mystical rock with good diving.', 38.8717, 1.2000, 'Spain', 'Spain', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Formentera', 'Crystal clear water.', 38.678395, 1.45, 'Spain', 'Spain', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Papagayo (Lanzarote)', 'Volcanic beaches with reef.', 28.8500, -13.8000, 'Spain', 'Spain', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Fuerteventura', 'Clear Atlantic water.', 28.0500, -14.3000, 'Spain', 'Spain', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sardina del Norte', 'Famous dive site.', 28.1500, -15.7000, 'Spain', 'Spain', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Abades (Tenerife)', 'Leper colony with reef.', 28.1333, -16.4333, 'Spain', 'Spain', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Hierro', 'Pristine volcanic island.', 27.777992, -18.031642, 'Spain', 'Spain', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chania Coast (Crete)', 'Northwest Crete.', 35.5217, 24.0167, 'Greece', 'Greece', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Antikythera', 'Remote island between Crete and Peloponnese.', 35.8450, 23.3000, 'Greece', 'Greece', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gavdos', 'Southernmost point of Europe.', 34.816192, 24.0833, 'Greece', 'Greece', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mykonos', 'Party island with diving.', 37.449997, 25.322786, 'Greece', 'Greece', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santorini Caldera', 'Volcanic caldera diving.', 36.4000, 25.4283, 'Greece', 'Greece', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rhodes', 'Knights of St John island.', 36.433298, 28.195436, 'Greece', 'Greece', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Zakynthos (Navagio)', 'Near famous shipwreck beach.', 37.8550, 20.6250, 'Greece', 'Greece', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Alonissos Marine Park', 'First Greek marine park.', 39.160813, 23.85, 'Greece', 'Greece', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kornati National Park', '140 islands, pristine water.', 43.8000, 15.3000, 'Croatia', 'Croatia', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Vis Island (Blue Cave)', 'Famous blue cave area.', 43.062096, 16.199858, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hvar Island - North', 'North coast. Lavender island.', 43.2317, 16.6500, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hvar Island - South', 'South coast. Open Adriatic.', 43.1117, 16.6500, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hvar Island - East', 'East coast. Channel side.', 43.1667, 16.7050, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hvar Island - West', 'West coast. Pakleni Islands.', 43.1667, 16.2450, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elafiti Islands (Dubrovnik)', 'Islands near Dubrovnik.', 42.6833, 17.9333, 'Croatia', 'Croatia', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kas', 'Lycian coast diving capital.', 36.1950, 29.6333, 'Turkey', 'Turkey', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bodrum Peninsula', 'Aegean coast with wrecks.', 37.0333, 27.4333, 'Turkey', 'Turkey', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fethiye (Oludeniz)', 'Blue Lagoon area.', 36.5450, 29.1167, 'Turkey', 'Turkey', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Jolla Cove', 'SHORE ENTRY - Marine preserve edges (check boundaries!). Rocky beach entry, kelp forest begins 50m offshore. Spearfishing allowed outside preserve boundary only.', 32.8500, -117.2772, 'Cali', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Jolla Shores', 'Sandy beach with kelp nearby.', 32.8589, -117.2606, 'Cali', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bird Rock (La Jolla)', 'Rocky reef area.', 32.8150, -117.2750, 'Cali', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Point Loma Kelp Beds', 'VERIFIED GPS - Extensive kelp forest offshore of Point Loma. White seabass, calicos, yellowtail, barracuda.', 32.7000, -117.2717, 'Cali', 'USA', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coronado Islands', 'Mexican waters, require permit.', 32.4167, -117.2550, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Casino Point (Catalina)', 'Underwater park, check rules.', 33.3500, -118.3250, 'Cali', 'USA', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Isthmus Cove (Catalina)', 'VERIFIED GPS - Two Harbors area reef structure. Good for calicos, yellowtail, barracuda.', 33.4467, -118.4883, 'Cali', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Farnsworth Bank (Catalina)', 'VERIFIED GPS - Underwater seamount/pinnacles 1.5mi SW of Ben Weston Point. 54-200ft depth. Purple hydrocoral, yellowtail, lingcod. State Marine Conservation Area.', 33.3400, -118.5192, 'Cali', 'USA', 'boat', 60, 60);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anacapa Island - West End', 'VERIFIED GPS - Closest Channel Island. West end reef structure with calicos, white seabass, halibut, yellowtail.', 34.0167, -119.4500, 'Cali', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Cruz Island - Smuggler''s Cove', 'VERIFIED GPS - Largest Channel Island. Smuggler''s Cove reef with white seabass, calicos, halibut, yellowtail.', 34.0223, -119.5373, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Rosa Island - North', 'North side of Santa Rosa. Exposed to NW swells, good lingcod.', 34.0350, -120.1000, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Rosa Island - South', 'South side of Santa Rosa. Protected from NW swells.', 33.8950, -120.1000, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Rosa Island - East', 'East side of Santa Rosa. Channel side.', 33.9500, -119.9450, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Rosa Island - West', 'West end of Santa Rosa. Most exposed, big fish.', 33.9500, -120.2050, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Miguel Island - North', 'North side of San Miguel. Very exposed, rough conditions.', 34.0883, -120.3667, 'Cali', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Miguel Island - South', 'South side of San Miguel. More protected.', 34.0083, -120.3667, 'Cali', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Miguel Island - East', 'East side of San Miguel. Channel between islands.', 34.0333, -120.3017, 'Cali', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Miguel Island - West', 'Westernmost point. Open ocean exposure.', 34.0333, -120.4617, 'Cali', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Leo Carrillo Beach', 'Rocky reef at Malibu border.', 34.0433, -118.9333, 'Cali', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Palos Verdes Point', 'Rocky peninsula.', 33.7500, -118.4167, 'Cali', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hermosa Beach Artificial Reef', 'Man-made reef structure.', 33.8500, -118.4050, 'Cali', 'USA', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Horseshoe Kelp (Redondo)', 'Offshore kelp forest.', 33.8000, -118.4500, 'Cali', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shaw''s Cove (Laguna)', 'Marine preserve adjacent.', 33.5433, -117.7967, 'Cali', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Diver''s Cove (Laguna)', 'Popular dive spot.', 33.5383, -117.7933, 'Cali', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Clemente - North', 'North end. Navy-controlled, check access.', 32.9450, -118.5000, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Clemente - South', 'South end. Pyramid Head area.', 32.8450, -118.5000, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Clemente - East', 'East coast. Facing mainland.', 32.9000, -118.4450, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('San Clemente - West', 'West coast. Open Pacific.', 32.9000, -118.5350, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Barbara Island - North', 'North coast. Remote Channel Island.', 33.5017, -119.0125, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Barbara Island - South', 'South coast. Excellent reef.', 33.4717, -119.0125, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Barbara Island - East', 'East coast. Facing mainland.', 33.4867, -118.9975, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Santa Barbara Island - West', 'West coast. Open Pacific.', 33.4867, -119.0275, 'Cali', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Monterey Breakwater', 'Protected dive area.', 36.6167, -121.8917, 'Cali', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Point Lobos Reserve', 'Some zones restricted.', 36.524345, -121.942814, 'Cali', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Jade Cove (Big Sur)', 'Remote jade hunting spot.', 35.9050, -121.4700, 'Cali', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Morro Rock', 'Iconic rock with diving.', 35.3700, -120.8750, 'Cali', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Port San Luis', 'Central coast access.', 35.1717, -120.7533, 'Cali', 'USA', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cortez Bank', 'VERIFIED GPS - Famous offshore seamount 100mi W of San Diego. Shallow high spot at 15ft. Bluefin tuna, yellowtail, yellowfin.', 32.4444, -119.1108, 'Cali', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tanner Bank', 'VERIFIED GPS - Offshore bank 60mi SW of San Diego. High spot at 80ft. Albacore, bluefin, yellowtail.', 32.7058, -119.1335, 'Cali', 'USA', 'boat', 80, 80);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('9-Mile Bank (North)', 'VERIFIED GPS - Offshore bank 9mi from Point Loma. Marlin, yellowtail, yellowfin, dorado, rockfish.', 32.6067, -117.4025, 'Cali', 'USA', 'boat', 50, 50);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('14-Mile Bank', 'VERIFIED GPS - Offshore bank SW of LA. Yellowtail, yellowfin, dorado, marlin.', 33.3987, -118.0003, 'Cali', 'USA', 'boat', 60, 60);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Horseshoe Kelp', 'VERIFIED GPS - Offshore kelp bed off Redondo. Calico, sand bass, sculpin, barracuda, yellowtail.', 33.6400, -118.2333, 'Cali', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Potter''s Reef (Horseshoe Drop-off)', 'VERIFIED GPS - Reef structure off Long Beach. Calico, sand bass, sculpin, yellowtail.', 33.6465, -118.2718, 'Cali', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Jolla Kelp (Offshore)', 'VERIFIED GPS - Offshore kelp beds SW of La Jolla. White seabass, calico, sand bass, yellowtail.', 32.8217, -117.2883, 'Cali', 'USA', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('60-Mile Bank', 'VERIFIED GPS - Remote offshore bank. Albacore, bluefin, yellowtail, yellowfin, dorado.', 32.0611, -118.2190, 'Cali', 'USA', 'boat', 100, 100);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Osborne Bank', 'VERIFIED GPS - Offshore bank between mainland and islands. Albacore, bluefin, yellowtail, yellowfin, marlin.', 33.3600, -119.0400, 'Cali', 'USA', 'boat', 80, 80);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Avalon Bank (228 Spot)', 'VERIFIED GPS - Bank NE of Catalina. Marlin, yellowtail, yellowfin, dorado.', 33.4097, -118.2217, 'Cali', 'USA', 'boat', 70, 70);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mackerel Bank', 'VERIFIED GPS - Offshore bank south of Santa Catalina. Marlin, yellowfin, yellowtail, dorado.', 33.0405, -118.5419, 'Cali', 'USA', 'boat', 90, 90);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hermosa Beach Artificial Reef', 'VERIFIED GPS - Man-made reef structure offshore of Hermosa.', 33.8537, -118.4133, 'Cali', 'USA', 'boat', 50, 50);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Redondo Beach Artificial Reef', 'VERIFIED GPS - Man-made reef structure offshore of Redondo.', 33.8372, -118.4089, 'Cali', 'USA', 'boat', 50, 50);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Torrey Pines Artificial Reef', 'VERIFIED GPS - Artificial reef offshore of Torrey Pines.', 32.8930, -117.2639, 'Cali', 'USA', 'boat', 45, 45);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Oceanside Artificial Reef', 'VERIFIED GPS - Large 256-acre artificial reef complex. Multiple depth zones 40-80ft.', 33.2097, -117.4300, 'Cali', 'USA', 'boat', 50, 50);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Huntington Flats', 'VERIFIED GPS - Shallow reef structure off Huntington Beach. Sand bass, sculpin, barracuda, yellowtail.', 33.6412, -118.0547, 'Cali', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Espiritu Santo - North', 'North end. La Paz hunting ground.', 24.5283, -110.3333, 'Mexico', 'Mexico', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Espiritu Santo - South', 'South end. Los Islotes nearby.', 24.3983, -110.3333, 'Mexico', 'Mexico', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Espiritu Santo - East', 'East coast. Sea of Cortez.', 24.4833, -110.2883, 'Mexico', 'Mexico', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Espiritu Santo - West', 'West coast. Facing La Paz.', 24.4833, -110.3983, 'Mexico', 'Mexico', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Isla Cerralvo', 'Remote island with pristine conditions.', 24.140599, -109.85, 'Mexico', 'Mexico', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Bajo Seamount', 'Hammerheads and big pelagics.', 24.5833, -110.2833, 'Mexico', 'Mexico', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Reina', 'Pinnacle with sea lions.', 24.5500, -110.3167, 'Mexico', 'Mexico', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cabo Pulmo National Park', 'No-take zone, check boundaries.', 23.4333, -109.4167, 'Mexico', 'Mexico', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gordo Banks', 'Two seamounts with hammerheads.', 23.0500, -109.4167, 'Mexico', 'Mexico', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Land''s End (Cabo)', 'Famous arch with reef.', 22.8750, -109.8917, 'Mexico', 'Mexico', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cape Kri', 'World record 374 fish species.', -0.5500, 130.6667, 'Raja', 'Raja', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Blue Magic', 'Oceanic mantas and sharks.', -0.5333, 130.6833, 'Raja', 'Raja', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sardines Reef', 'Second most biodiverse.', -0.5600, 130.6500, 'Raja', 'Raja', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Passage', 'Narrow channel with tidal flow.', -0.4333, 130.5500, 'Raja', 'Raja', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Misool', 'Remote southern area.', -1.744783, 129.9833, 'Raja', 'Raja', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cairns Outer Reef', 'Great Barrier Reef day trips.', -16.7500, 146.0000, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ribbon Reefs', 'Pristine northern GBR.', -14.7500, 145.6500, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coral Bay (Ningaloo)', 'Whale sharks March-July.', -23.1500, 113.7667, 'Aus', 'Aus', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Exmouth (Ningaloo)', 'Navy Pier legendary dive.', -21.9333, 114.144957, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Montague Island - North', 'North coast. Huge kingfish.', -36.2350, 150.2333, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Montague Island - South', 'South coast. Temperate water.', -36.2650, 150.2333, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Montague Island - East', 'East coast. Open Tasman.', -36.2500, 150.2483, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Montague Island - West', 'West coast. Facing mainland.', -36.2500, 150.2183, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coron Bay Wrecks', 'WWII Japanese shipwrecks.', 11.9833, 120.1950, 'Phil', 'Phil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tubbataha Reef - North', 'North atoll. UNESCO site, mantas.', 8.9483, 119.9000, 'Phil', 'Phil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tubbataha Reef - South', 'South atoll. Hammerhead cleaning station.', 8.9183, 119.9000, 'Phil', 'Phil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tubbataha Reef - East', 'East wall. Deep drop-off.', 8.9333, 119.9150, 'Phil', 'Phil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tubbataha Reef - West', 'West wall. Sunrise dives.', 8.9333, 119.8850, 'Phil', 'Phil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Apo Reef', 'Second largest contiguous reef.', 12.6500, 120.4500, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Malapascua', 'Thresher sharks at dawn.', 11.3350, 124.1200, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Balicasag - North', 'North coast. Bohol diving gem.', 9.5317, 123.6783, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Balicasag - South', 'South coast. Jack point.', 9.5017, 123.6783, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Balicasag - East', 'East coast. Diver''s Heaven.', 9.5167, 123.6933, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Balicasag - West', 'West coast. Cathedral wall.', 9.5167, 123.6633, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Beqa Lagoon - North', 'North side. Eight shark species at Cathedral.', -18.3683, 177.9833, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Beqa Lagoon - South', 'South side. Open Pacific exposure.', -18.3983, 177.9833, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Beqa Lagoon - East', 'East side. Strong currents.', -18.3833, 178.0083, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Beqa Lagoon - West', 'West side. Closest to Pacific Harbour.', -18.3833, 177.9683, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Astrolabe Reef - North', 'North end. 120km barrier reef.', -19.0350, 178.2500, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Astrolabe Reef - South', 'South end. Remote diving.', -19.0650, 178.2500, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Astrolabe Reef - East', 'East side. Open Pacific.', -19.0500, 178.2650, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Astrolabe Reef - West', 'West side. Inside lagoon.', -19.0500, 178.2350, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taveuni Rainbow - North', 'North side. Soft coral capital.', -16.8850, 179.9000, 'Fiji', 'Fiji', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taveuni Rainbow - South', 'South side. Great White Wall.', -16.9150, 179.9000, 'Fiji', 'Fiji', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taveuni Rainbow - East', 'East side. Somosomo Strait.', -16.9000, 179.9150, 'Fiji', 'Fiji', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Taveuni Rainbow - West', 'West side. Taveuni shore.', -16.9000, 179.8850, 'Fiji', 'Fiji', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Namena Marine Reserve', 'Protected area.', -17.1000, 179.1000, 'Fiji', 'Fiji', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Maaya Thila', 'Top Maldives site.', 3.8833, 72.9000, 'Maldives', 'Maldives', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fish Head', 'Shark cleaning station.', 3.9000, 72.9167, 'Maldives', 'Maldives', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Manta Point (Lankanfinolhu)', 'Manta cleaning station.', 4.2500, 72.9500, 'Maldives', 'Maldives', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hanifaru Bay', 'Manta aggregation June-Nov.', 5.2500, 73.0833, 'Maldives', 'Maldives', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Elphinstone Reef', 'Oceanic whitetips and hammerheads.', 25.3167, 34.8667, 'Egypt', 'Egypt', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Brothers Islands', 'Remote with sharks.', 26.3167, 34.8500, 'Egypt', 'Egypt', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Daedalus Reef', 'Remote reef with hammerheads.', 24.9167, 35.8500, 'Egypt', 'Egypt', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Jackson Reef', 'Straits of Tiran.', 27.9500, 34.4667, 'Egypt', 'Egypt', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('SS Thistlegorm', 'Famous WWII wreck.', 27.8167, 33.9167, 'Egypt', 'Egypt', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Two Mile Reef (Sodwana)', 'Subtropical reef.', -27.5333, 32.6833, 'Sa', 'Sa', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Aliwal Shoal', 'Ragged-tooth sharks.', -30.2667, 30.8333, 'Sa', 'Sa', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Protea Banks', 'Deep water shark action.', -30.7000, 30.5500, 'Sa', 'Sa', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Simon''s Town', 'Cold water diving.', -34.1833, 18.4333, 'Sa', 'Sa', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goat Island - North', 'North coast. Marine reserve.', -36.2517, 174.8000, 'Nz', 'New Zealand', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goat Island - South', 'South coast. Check boundaries.', -36.3117, 174.8000, 'Nz', 'New Zealand', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goat Island - East', 'East coast. Ocean side.', -36.2667, 174.8150, 'Nz', 'New Zealand', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Goat Island - West', 'West coast. Mainland side.', -36.2667, 174.7850, 'Nz', 'New Zealand', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Barrier - North', 'North coast. Remote with clear water.', -36.0450, 175.4000, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Barrier - South', 'South coast. Sheltered.', -36.2750, 175.4000, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Barrier - East', 'East coast. Pacific exposure.', -36.2000, 175.4950, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Barrier - West', 'West coast. Hauraki Gulf.', -36.2000, 175.3450, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bay of Islands', 'Multiple dive spots.', -35.2500, 174.1000, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fiordland', 'Unique black coral.', -45.435153, 167.123698, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Great Blue Hole', 'Famous sinkhole.', 17.3158, -87.5350, 'Belize', 'Belize', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Elbow (Turneffe)', 'Southern tip with currents.', 17.1833, -87.8000, 'Belize', 'Belize', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Half Moon Caye', 'UNESCO site.', 17.2000, -87.5333, 'Belize', 'Belize', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Glover''s Reef', 'Remote atoll.', 16.7500, -87.8000, 'Belize', 'Belize', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Desecheo Island', 'Wildlife refuge, 13 miles offshore.', 18.3833, -67.4883, 'Pr', 'Pr', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mona Island - North', 'North coast. Permit required.', 18.1283, -67.9000, 'Pr', 'Pr', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mona Island - South', 'South coast. Remote diving.', 18.0483, -67.9000, 'Pr', 'Pr', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mona Island - East', 'East coast. Facing PR.', 18.0833, -67.8350, 'Pr', 'Pr', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mona Island - West', 'West coast. Mona Passage.', 18.0833, -67.9550, 'Pr', 'Pr', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('La Parguera Walls', 'Southwest coast walls.', 17.9667, -67.0500, 'Pr', 'Pr', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fajardo Reefs', 'East coast diving.', 18.3383, -65.6333, 'Pr', 'Pr', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cape Hatteras Offshore', 'Gulf Stream meets Labrador Current.', 35.2167, -75.5333, 'Nc', 'Nc', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Diamond Shoals', 'Treacherous but fishful.', 35.1500, -75.4000, 'Nc', 'Nc', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cape Lookout Shoals', 'Southern Outer Banks.', 34.5833, -76.5333, 'Nc', 'Nc', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Papoose Wreck', 'Tanker sunk by U-boat.', 34.6667, -76.5167, 'Nc', 'Nc', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('U-352 Wreck', 'German U-boat.', 34.6000, -76.6833, 'Nc', 'Nc', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tunnels Beach (Makua)', 'Famous reef with lava tubes. Summer only.', 22.2269, -159.5697, 'Kauai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Poipu Beach', 'South shore with good reef structure.', 21.8725, -159.4594, 'Kauai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anini Beach', 'Protected reef on north shore.', 22.2283, -159.4583, 'Kauai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kee Beach', 'End of road, pristine reef.', 22.2228, -159.5864, 'Kauai', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lydgate Beach', 'Protected pools plus outside reef.', 22.041699, -159.326954, 'Kauai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Salt Pond Beach', 'West side with good visibility.', 21.9000, -159.6083, 'Kauai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Polihale Beach', 'Remote west side, difficult access.', 22.0789, -159.7661, 'Kauai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Koloa Landing', 'Historic landing with reef.', 21.8614, -159.4636, 'Kauai', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lawai Beach', 'Near Spouting Horn with reef.', 21.8722, -159.4917, 'Kauai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hanalei Bay', 'Large bay with reef edges.', 22.2094, -159.5072, 'Kauai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Haena Reef', 'Outer reef beyond Tunnels.', 22.2250, -159.5650, 'Kauai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Niihau - North', 'North coast. Forbidden Island, charter only.', 21.9550, -160.1500, 'Kauai', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Niihau - South', 'South coast. Pristine waters.', 21.8550, -160.1500, 'Kauai', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Niihau - East', 'East coast. Facing Kauai.', 21.9000, -160.0650, 'Kauai', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Niihau - West', 'West coast. Open Pacific.', 21.9000, -160.2250, 'Kauai', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lehua Rock', 'Volcanic islet near Niihau. Remote diving.', 22.0167, -160.1050, 'Kauai', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Port Allen Harbor', 'Harbor reef and offshore.', 21.8972, -159.5917, 'Kauai', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Na Pali Coast', 'Remote coast, summer only.', 22.1833, -159.6333, 'Kauai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Princeville Reefs', 'North shore reef system.', 22.23111, -159.4667, 'Kauai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kipu Kai', 'Private beach, boat access.', 21.88981, -159.405719, 'Kauai', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Nawiliwili Harbor Reef', 'Harbor entrance reef.', 21.9550, -159.3500, 'Kauai', 'USA', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anahola Bay', 'East side bay with reef.', 22.149346, -159.300045, 'Kauai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Moloaa Bay', 'Quiet east side bay.', 22.195397, -159.320235, 'Kauai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaunakakai Wharf', 'Historic wharf with fish aggregation.', 21.0833, -157.0167, 'Molokai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Papohaku Beach', 'Longest white sand beach in Hawaii.', 21.176889, -157.260927, 'Molokai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kawakiu Bay', 'West end with clear water.', 21.1833, -157.2667, 'Molokai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kepuhi Bay', 'Resort area with reef.', 21.174999, -157.260047, 'Molokai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Murphy Beach', 'East side with calm water.', 21.0783, -156.7833, 'Molokai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Halawa Bay', 'Remote east end with valley.', 21.16441, -156.7333, 'Molokai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('South Shore Reef', 'Extensive fringing reef.', 21.0667, -157.0000, 'Molokai', 'USA', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rock Point', 'West Molokai diving spot.', 21.1500, -157.2883, 'Molokai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Penguin Bank', 'Offshore bank with big fish.', 20.8333, -157.2500, 'Molokai', 'USA', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kalaupapa Peninsula', 'Historic leper colony, permit required.', 21.203797, -156.953724, 'Molokai', 'USA', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hulopoe Bay Marine Preserve', 'Marine preserve, limited take.', 20.7333, -156.9167, 'Lanai', 'USA', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shark Fin Rock', 'Iconic rock formation with reef.', 20.734722, -156.916965, 'Lanai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Manele Bay', 'Harbor with surrounding reef.', 20.7417, -156.8867, 'Lanai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Shipwreck Beach', 'North shore with wrecks.', 20.926101, -156.9167, 'Lanai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cathedrals', 'Famous lava tube diving.', 20.7250, -156.9250, 'Lanai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('First Cathedral', 'Primary cathedral site.', 20.7260, -156.9270, 'Lanai', 'USA', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Second Cathedral', 'Second lava tube system.', 20.7240, -156.9230, 'Lanai', 'USA', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lighthouse Point', 'Southern tip with current.', 20.7167, -156.9000, 'Lanai', 'USA', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaumalapau Harbor', 'Pineapple shipping harbor.', 20.7833, -156.9967, 'Lanai', 'USA', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Polihua Beach', 'Remote north beach.', 20.933808, -156.9833, 'Lanai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lopa Beach', 'East side with reef.', 20.86765, -156.829934, 'Lanai', 'USA', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Palm Beach Inlet', 'Strong currents, big fish.', 26.7700, -80.0333, 'Fl', 'Fl', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Jupiter Ledge', 'Natural reef ledge.', 26.9500, -80.0500, 'Fl', 'Fl', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Breakers Reef (Palm Beach)', 'Near famous hotel.', 26.7217, -80.0333, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Delray Ledge', 'Inshore ledge system.', 26.4500, -80.0500, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Boynton Ledge', 'Good hogfish habitat.', 26.5383, -80.0500, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Boca Raton Inlet', 'Inlet with reef structure.', 26.3333, -80.0667, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hillsboro Inlet', 'Lighthouse inlet with reef.', 26.2583, -80.0750, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fort Lauderdale Reef', 'Artificial reef complex.', 26.1000, -80.0833, 'Fl', 'Fl', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hollywood Beach Reef', 'Natural reef.', 26.0000, -80.1000, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Haulover Inlet', 'Miami inlet with structure.', 25.9000, -80.1167, 'Fl', 'Fl', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Government Cut (Miami)', 'Port of Miami entrance.', 25.7667, -80.1383, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fowey Rocks', 'Lighthouse reef.', 25.5900, -80.0967, 'Fl', 'Fl', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Triumph Reef', 'Offshore reef.', 25.5000, -80.1167, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ajax Reef', 'Upper Keys reef.', 25.3833, -80.1833, 'Fl', 'Fl', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('St. Augustine Reef', 'Northeast Florida reef.', 29.8667, -81.2167, 'Fl', 'Fl', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Daytona Ledge', 'Central Florida ledge.', 29.2000, -80.9833, 'Fl', 'Fl', 'boat', 22, 22);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sebastian Inlet', 'Famous for snook.', 27.8583, -80.4500, 'Fl', 'Fl', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fort Pierce Reef', 'Artificial reef complex.', 27.4667, -80.2667, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Skyway Bridge', 'Massive bridge structure.', 27.6167, -82.6500, 'Fl', 'Fl', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Egmont Key', 'Historic island with reef.', 27.5983, -82.7667, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Clearwater Artificial Reef', 'Multiple artificial reefs.', 27.9667, -83.0000, 'Fl', 'Fl', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tarpon Springs Reef', 'Sponge diving heritage area.', 28.1500, -82.8500, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Steinhatchee Reef', 'Big Bend natural reef.', 29.642578, -83.413544, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cedar Key Reef', 'Natural reef complex.', 29.1333, -83.1333, 'Fl', 'Fl', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Destin Bridge Rubble', 'Bridge rubble artificial reef.', 30.3783, -86.5000, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Destin Offshore Reefs', 'Deep Gulf artificial reefs.', 30.2500, -86.6000, 'Fl', 'Fl', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Panama City Artificial Reef', 'Extensive reef complex.', 30.0500, -85.8000, 'Fl', 'Fl', 'boat', 22, 22);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pensacola Rigs', 'Oil rig structures.', 30.2000, -87.3000, 'Fl', 'Fl', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('USS Oriskany', 'Aircraft carrier artificial reef.', 30.0333, -87.0167, 'Fl', 'Fl', 'boat', 45, 45);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Naples Reef', 'Southwest Florida reef.', 26.1000, -81.8500, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sanibel Island Reef', 'Near shell island.', 26.4333, -82.1500, 'Fl', 'Fl', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Boca Grande Pass', 'Famous tarpon spot.', 26.7167, -82.2667, 'Fl', 'Fl', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Venice Ledge', 'Shark tooth capital area.', 27.0667, -82.5500, 'Fl', 'Fl', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fernando de Noronha - North', 'North coast. UNESCO marine park.', -3.8294, -32.4250, 'Brazil', 'Brazil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fernando de Noronha - South', 'South coast. Baia dos Porcos.', -3.8894, -32.4250, 'Brazil', 'Brazil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fernando de Noronha - East', 'East coast. Mar de Dentro.', -3.8544, -32.3900, 'Brazil', 'Brazil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fernando de Noronha - West', 'West coast. Mar de Fora.', -3.8544, -32.4500, 'Brazil', 'Brazil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Baia do Sancho', 'World''s most beautiful beach.', -3.8450, -32.4333, 'Brazil', 'Brazil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Baia dos Golfinhos', 'Spinner dolphin bay.', -3.8333, -32.4167, 'Brazil', 'Brazil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Abrolhos Archipelago', 'Humpback whale breeding ground.', -17.9667, -38.7000, 'Brazil', 'Brazil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Arraial do Cabo', 'Brazilian Caribbean, coldest water.', -22.9717, -42.0167, 'Brazil', 'Brazil', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ilha Grande', 'Large island near Rio.', -23.115641, -44.195947, 'Brazil', 'Brazil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Laje de Santos', 'Offshore island marine park.', -24.3167, -46.1833, 'Brazil', 'Brazil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ilhabela', 'Island near São Paulo.', -23.783299, -45.365747, 'Brazil', 'Brazil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buzios', 'Resort peninsula.', -22.7500, -41.8883, 'Brazil', 'Brazil', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Porto de Galinhas', 'Natural pools at low tide.', -8.5000, -35.0000, 'Brazil', 'Brazil', 'shore', 8, 8);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Maragogi', 'Caribbean of Brazil.', -9.0167, -35.2167, 'Brazil', 'Brazil', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Recife Offshore', 'Shipwrecks off Recife.', -8.0500, -34.8667, 'Brazil', 'Brazil', 'boat', 22, 22);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tambau Reef', 'Urban reef João Pessoa.', -7.1167, -34.8167, 'Brazil', 'Brazil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Natal Reefs', 'Rio Grande do Norte.', -5.77311, -35.189759, 'Brazil', 'Brazil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Atol das Rocas', 'Only atoll in South Atlantic.', -3.8667, -33.8167, 'Brazil', 'Brazil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ilha da Trindade', 'Remote volcanic island.', -20.521802, -29.322148, 'Brazil', 'Brazil', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Salvador Reefs', 'Bahia coast reefs.', -13.01441, -38.5167, 'Brazil', 'Brazil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Praia do Forte', 'Sea turtle area.', -12.5833, -37.9833, 'Brazil', 'Brazil', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Morro de São Paulo', 'Island village.', -13.375654, -38.908841, 'Brazil', 'Brazil', 'boat', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Florianópolis', 'Southern island city.', -27.5833, -48.5500, 'Brazil', 'Brazil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kerama Islands', 'National park with pristine water.', 26.1833, 127.3167, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Miyako Islands', 'Three islands with clear water.', 24.821634, 125.276162, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ishigaki - North', 'North coast. Manta ray capital.', 24.343782, 124.145, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ishigaki - South', 'South coast. Kabira Bay.', 24.3183, 124.1450, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ishigaki - East', 'East coast. Shiraho coral.', 24.3333, 124.2000, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ishigaki - West', 'West coast. Manta Point.', 24.3333, 124.1300, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Iriomote - North', 'North coast. Remote jungle island.', 24.4550, 123.8500, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Iriomote - South', 'South coast. Pristine waters.', 24.2550, 123.8500, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Iriomote - East', 'East coast. Facing Ishigaki.', 24.3000, 123.9150, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Iriomote - West', 'West coast. Open ocean.', 24.3000, 123.6450, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yonaguni - North', 'North coast. Hammerhead capital.', 24.4750, 122.9500, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yonaguni - South', 'South coast. Underwater ruins.', 24.4350, 122.9500, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yonaguni - East', 'East coast. Open ocean.', 24.4500, 123.0350, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yonaguni - West', 'West coast. Taiwan strait.', 24.453194, 122.931491, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chichijima (Ogasawara)', 'UNESCO World Heritage.', 27.083299, 142.228845, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hahajima (Ogasawara)', 'Mother island with virgin waters.', 26.6500, 142.1450, 'Japan', 'Japan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Izu Oshima', 'Volcanic island near Tokyo.', 34.7500, 139.3500, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hachijojima', 'Subtropical island.', 33.092354, 139.774174, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mikurajima', 'Dolphin swimming island.', 33.849592, 139.6, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Izu Peninsula', 'Popular diving area.', 34.801996, 139.069282, 'Japan', 'Japan', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kushimoto', 'Southernmost Honshu, coral reef.', 33.4667, 135.7883, 'Japan', 'Japan', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Amami Oshima', 'Between Okinawa and Kyushu.', 28.3833, 129.4950, 'Japan', 'Japan', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yakushima', 'Ancient cedar island.', 30.416128, 130.423293, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tanegashima', 'Rocket launch island.', 30.4833, 130.9667, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tokunoshima', 'Amami group island.', 27.795397, 129.013675, 'Japan', 'Japan', 'boat', 22, 22);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Okinoerabujima', 'Cave diving paradise.', 27.404905, 128.55, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Yoronjima', 'Clear water paradise.', 27.0283, 128.4167, 'Japan', 'Japan', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Nagannu Island (Okinawa)', 'Coral cay day trip.', 26.2667, 127.5333, 'Japan', 'Japan', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Zamami - North', 'North coast. Kerama diving.', 26.2483, 127.3000, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Zamami - South', 'South coast. Whale watching.', 26.2183, 127.3000, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Zamami - East', 'East coast. Facing Okinawa.', 26.22947, 127.32927, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Zamami - West', 'West coast. Open sea.', 26.2333, 127.2750, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tokashiki - North', 'North coast. Largest Kerama island.', 26.2283, 127.3667, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tokashiki - South', 'South coast. Aharen Beach.', 26.1683, 127.3667, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tokashiki - East', 'East coast. Facing Okinawa.', 26.1833, 127.3817, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tokashiki - West', 'West coast. Open Pacific.', 26.1833, 127.3317, 'Japan', 'Japan', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Aka and Geruma Islands', 'Kerama group.', 26.207646, 127.291822, 'Japan', 'Japan', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tulamben (USAT Liberty)', 'Famous WWII wreck.', -8.2700, 115.5917, 'Indo', 'Indo', 'shore', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Amed', 'Fishing village with reef.', -8.331543, 115.651953, 'Indo', 'Indo', 'shore', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Nusa Penida', 'Manta rays and mola mola.', -8.774011, 115.492104, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Menjangan - North', 'North coast. NW Bali marine park.', -8.0850, 114.5000, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Menjangan - South', 'South coast. Pos 2.', -8.3050, 114.5000, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Menjangan - East', 'East coast. Wall diving.', -8.1000, 114.5150, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Menjangan - West', 'West coast. Anchor wreck.', -8.1000, 114.4250, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Batu Bolong (Komodo)', 'Current-swept pinnacle.', -8.475722, 119.4833, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Manta Point (Komodo)', 'Manta cleaning station.', -8.6833, 119.402123, 'Indo', 'Indo', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Castle Rock (Komodo)', 'Submerged seamount.', -8.4667, 119.5167, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Crystal Rock (Komodo)', 'Pinnacle with soft coral.', -8.4833, 119.5000, 'Indo', 'Indo', 'boat', 22, 22);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bunaken Marine Park', 'World-class wall diving.', 1.6333, 124.7500, 'Indo', 'Indo', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lembeh Strait', 'Muck diving capital.', 1.4617, 125.2333, 'Indo', 'Indo', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Togian Islands', 'Remote central Sulawesi.', -0.433808, 121.9333, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Wakatobi', 'Premium dive resort area.', -5.4833, 123.9000, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Banda Islands', 'Nutmeg islands with hammerheads.', -4.5333, 129.9000, 'Indo', 'Indo', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Alor', 'Remote eastern Indonesia.', -8.236102, 124.5333, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Flores (Maumere)', 'Eastern Flores diving.', -8.6117, 122.2167, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gili Islands', 'Three islands off Lombok.', -8.357646, 116.025572, 'Indo', 'Indo', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pulau Weh (Sabang)', 'Tip of Sumatra.', 5.8833, 95.3167, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fam Islands (Raja Ampat)', 'Spectacular lagoons.', -0.4500, 130.3833, 'Indo', 'Indo', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Wayag (Raja Ampat)', 'Iconic karst landscape.', 0.2000, 130.0500, 'Indo', 'Indo', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cenderawasih Bay', 'Whale shark fishermen.', -2.833298, 134.507643, 'Indo', 'Indo', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anilao', 'Macro diving capital.', 13.7667, 120.918464, 'Phil', 'Phil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Verde Island - North', 'North side. Center of marine biodiversity.', 13.5817, 121.0667, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Verde Island - South', 'South side. Passage diving.', 13.5317, 121.0667, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Verde Island - East', 'East side. Drop-offs.', 13.5667, 121.1017, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Verde Island - West', 'West side. Wall diving.', 13.5667, 121.0517, 'Phil', 'Phil', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Puerto Galera', 'Dive capital of Philippines.', 13.5217, 120.9500, 'Phil', 'Phil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Dauin (Dumaguete)', 'Muck diving and whale sharks.', 9.1833, 123.2667, 'Phil', 'Phil', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Moalboal', 'Sardine run and reef.', 9.957646, 123.391063, 'Phil', 'Phil', 'shore', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Oslob', 'Whale shark interaction.', 9.4667, 123.4000, 'Phil', 'Phil', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Panglao Island', 'Bohol diving base.', 9.5450, 123.7667, 'Phil', 'Phil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Boracay', 'Party island with diving.', 11.9667, 121.9167, 'Phil', 'Phil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Siargao', 'Surf and dive island.', 9.8550, 126.1000, 'Phil', 'Phil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Camiguin', 'Island born of fire.', 9.217653, 124.768104, 'Phil', 'Phil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Siquijor', 'Mystical island.', 9.234353, 123.548495, 'Phil', 'Phil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Nido', 'Palawan limestone karsts.', 11.1883, 119.3833, 'Phil', 'Phil', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hundred Islands', 'National park.', 16.21441, 119.9167, 'Phil', 'Phil', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Romblon', 'Marble island.', 12.5833, 122.2667, 'Phil', 'Phil', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Flinders Reef', 'SE Queensland pinnacle.', -26.9833, 153.4833, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gold Coast Seaway Reef', 'Artificial reef.', -27.9333, 153.4333, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Julian Rocks', 'Byron Bay marine reserve.', -28.6333, 153.6333, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Fish Rock Cave', 'South West Rocks.', -30.8833, 153.0667, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Magic Point', 'Sydney grey nurse site.', -33.9667, 151.2667, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Jervis Bay', 'Marine park with seals.', -35.0833, 150.7167, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Portsea Pier', 'Port Phillip Bay diving.', -38.3167, 144.7167, 'Aus', 'Aus', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rapid Bay Jetty', 'Leafy sea dragon.', -35.5167, 138.1833, 'Aus', 'Aus', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rottnest - North', 'North coast. Perth''s dive island.', -31.9850, 115.5000, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rottnest - South', 'South coast. More exposed.', -32.0250, 115.5000, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rottnest - East', 'East coast. Facing Perth.', -32.003194, 115.538767, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rottnest - West', 'West coast. Open Indian Ocean.', -32.0000, 115.4850, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Abrolhos Islands', 'Remote WA diving.', -28.7167, 113.7783, 'Aus', 'Aus', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Rowley Shoals', 'Remote WA atolls.', -17.3333, 119.3333, 'Aus', 'Aus', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Gove Peninsula', 'Remote NT diving.', -12.181543, 136.802182, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('SS Yongala', 'Australia''s best wreck.', -19.3000, 147.6167, 'Aus', 'Aus', 'boat', 28, 28);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cod Hole', 'Potato cod feeding.', -14.6833, 145.6333, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Osprey Reef', 'Coral Sea pinnacle.', -13.8833, 146.5500, 'Aus', 'Aus', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lady Elliot - North', 'North side. Southern GBR.', -24.1017, 152.7167, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lady Elliot - South', 'South side. Manta cleaning.', -24.1317, 152.7167, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lady Elliot - East', 'East side. Coral bombies.', -24.1167, 152.7317, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Lady Elliot - West', 'West side. Lighthouse.', -24.1167, 152.7017, 'Aus', 'Aus', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Heron Island - North', 'North side. Research station.', -23.4183, 151.9167, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Heron Island - South', 'South side. Heron Bommie.', -23.4583, 151.9167, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Heron Island - East', 'East side. Blue pools.', -23.4333, 151.9317, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Heron Island - West', 'West side. Harbour area.', -23.4333, 151.9017, 'Aus', 'Aus', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Poor Knights - North', 'North side. Top 10 dive site globally.', -35.4417, 174.7333, 'Nz', 'New Zealand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Poor Knights - South', 'South side. Sheltered from NW swells.', -35.4867, 174.7333, 'Nz', 'New Zealand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Poor Knights - East', 'East side. Open ocean exposure.', -35.4667, 174.7533, 'Nz', 'New Zealand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Poor Knights - West', 'West side. Mainland-facing, calmer.', -35.4667, 174.7133, 'Nz', 'New Zealand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tutukaka Coast', 'Gateway to Poor Knights.', -35.6167, 174.5333, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Leigh Marine Reserve', 'First NZ marine reserve.', -36.2667, 174.8000, 'Nz', 'New Zealand', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Hahei Marine Reserve', 'Cathedral Cove area.', -36.837902, 175.815115, 'Nz', 'New Zealand', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('White Island - North', 'North side. Active volcano.', -37.5017, 177.1833, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('White Island - South', 'South side. Crater view.', -37.5417, 177.1833, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('White Island - East', 'East side. Open ocean.', -37.5167, 177.1983, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('White Island - West', 'West side. Facing NZ.', -37.5167, 177.1583, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Wellington South Coast', 'Wellington diving.', -41.3500, 174.8000, 'Nz', 'New Zealand', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Kaikoura', 'Whale watching and diving.', -42.409054, 173.693655, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Marlborough Sounds', 'Sheltered diving.', -41.171632, 174.028743, 'Nz', 'New Zealand', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Milford Sound', 'Black coral and fiords.', -44.618021, 167.871462, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Doubtful Sound', 'Remote fiord diving.', -45.3000, 166.9667, 'Nz', 'New Zealand', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stewart Island - North', 'North coast. Remote southern diving.', -46.5950, 167.8500, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stewart Island - South', 'South coast. Sub-Antarctic waters.', -47.2050, 167.8500, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stewart Island - East', 'East coast. Paterson Inlet.', -47.0000, 168.2550, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stewart Island - West', 'West coast. Fiordland style.', -47.0000, 167.6450, 'Nz', 'New Zealand', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Chatham Islands', 'Remote Pacific outpost.', -43.9500, -176.5500, 'Nz', 'New Zealand', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Tofo Beach', 'Whale shark and manta capital.', -23.83981, 35.54444, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Barra Beach', 'Near Tofo with reef.', -23.7833, 35.5000, 'Moz', 'Moz', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bazaruto Archipelago', 'Dugong habitat.', -21.666698, 35.494786, 'Moz', 'Moz', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Inhambane Coast', 'Historic Portuguese town.', -23.8667, 35.3783, 'Moz', 'Moz', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ponta do Ouro', 'SA border with dolphins.', -26.8333, 32.8883, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pemba (Quirimbas)', 'Northern Mozambique.', -12.95229, 40.5167, 'Moz', 'Moz', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ibo Island - North', 'North coast. Historic fortress.', -12.3150, 40.6000, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ibo Island - South', 'South coast. Quirimbas.', -12.3950, 40.6000, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ibo Island - East', 'East coast. Indian Ocean.', -12.3500, 40.6250, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Ibo Island - West', 'West coast. Channel side.', -12.3500, 40.5850, 'Moz', 'Moz', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mafia Island - North', 'North coast. Whale shark park.', -7.909346, 39.791019, 'Tanzania', 'Tanzania', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mafia Island - South', 'South coast. Chole Bay.', -7.9317, 39.7833, 'Tanzania', 'Tanzania', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mafia Island - East', 'East coast. Open ocean.', -7.9167, 39.7983, 'Tanzania', 'Tanzania', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mafia Island - West', 'West coast. Mainland side.', -7.9167, 39.7683, 'Tanzania', 'Tanzania', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mnemba Atoll', 'Private island diving.', -5.8167, 39.3833, 'Tanzania', 'Tanzania', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pemba Island - North', 'North coast. Clove island.', -4.8783, 39.7500, 'Tanzania', 'Tanzania', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pemba Island - South', 'South coast. Deep walls.', -5.5383, 39.7500, 'Tanzania', 'Tanzania', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pemba Island - East', 'East coast. Misali Island.', -5.0333, 39.8450, 'Tanzania', 'Tanzania', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pemba Island - West', 'West coast. Shimba Hills.', -5.026084, 39.685, 'Tanzania', 'Tanzania', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Watamu Marine Park', 'Kenya''s premier diving.', -3.360813, 40.0167, 'Kenya', 'Kenya', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Malindi', 'Historic Swahili coast.', -3.20651, 40.126905, 'Kenya', 'Kenya', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Diani Beach', 'South coast diving.', -4.3167, 39.5833, 'Kenya', 'Kenya', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahe - North', 'North coast. Main Seychelles island.', -4.6483, 55.4833, 'Seychelles', 'Seychelles', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahe - South', 'South coast. More exposed.', -4.7383, 55.4833, 'Seychelles', 'Seychelles', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahe - East', 'East coast. Windward side.', -4.6833, 55.5383, 'Seychelles', 'Seychelles', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mahe - West', 'West coast. Sunset side, calmer.', -4.6833, 55.4383, 'Seychelles', 'Seychelles', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Praslin', 'Vallee de Mai island.', -4.3333, 55.764451, 'Seychelles', 'Seychelles', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Aldabra Atoll', 'UNESCO World Heritage.', -9.4167, 46.3333, 'Seychelles', 'Seychelles', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Flic en Flac', 'West coast diving.', -20.2833, 57.3617, 'Mauritius', 'Mauritius', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Blue Bay', 'Marine park.', -20.4500, 57.7167, 'Mauritius', 'Mauritius', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Saint-Leu', 'West coast diving.', -21.1667, 55.2833, 'Reunion', 'Reunion', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Saint-Gilles', 'Lagoon and outer reef.', -21.066699, 55.214967, 'Reunion', 'Reunion', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Coki Beach (St. Thomas)', 'Popular snorkel spot.', 18.3500, -64.8667, 'Usvi', 'Usvi', 'shore', 12, 12);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Trunk Bay (St. John)', 'Underwater trail.', 18.3550, -64.7717, 'Usvi', 'Usvi', 'shore', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buck Island - North', 'North side. National monument.', 17.7983, -64.6167, 'Usvi', 'Usvi', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buck Island - South', 'South side. Elkhorn coral.', 17.7683, -64.6167, 'Usvi', 'Usvi', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buck Island - East', 'East side. Underwater trail.', 17.7833, -64.6017, 'Usvi', 'Usvi', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buck Island - West', 'West side. Facing St. Croix.', 17.7833, -64.6317, 'Usvi', 'Usvi', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('RMS Rhone (BVI)', 'Famous wreck.', 18.3833, -64.5500, 'Bvi', 'Bvi', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('The Dogs (BVI)', 'Uninhabited island group.', 18.4667, -64.4333, 'Bvi', 'Bvi', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Stingray City (Grand Cayman)', 'Stingray sandbar.', 19.3833, -81.3000, 'Cayman', 'Cayman', 'boat', 4, 4);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bloody Bay Wall (Little Cayman)', 'Famous wall dive.', 19.7000, -80.0833, 'Cayman', 'Cayman', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('North Wall (Grand Cayman)', 'Dramatic drop-off.', 19.3833, -81.2333, 'Cayman', 'Cayman', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Montego Bay Marine Park', 'Protected area.', 18.4833, -77.9333, 'Jamaica', 'Jamaica', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Negril', 'West coast diving.', 18.283808, -78.35, 'Jamaica', 'Jamaica', 'boat', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Mushroom Forest (Curacao)', 'Unique coral formations.', 12.3667, -69.1550, 'Curacao', 'Curacao', 'shore', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Blue Bay (Curacao)', 'Golf course reef.', 12.1333, -68.9883, 'Curacao', 'Curacao', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('1000 Steps (Bonaire)', 'Shore dive classic.', 12.2167, -68.3500, 'Bonaire', 'Bonaire', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Salt Pier (Bonaire)', 'Pier diving.', 12.1000, -68.2833, 'Bonaire', 'Bonaire', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Antilla Wreck (Aruba)', 'WWII German freighter.', 12.6000, -70.0550, 'Aruba', 'Aruba', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Catalina Island (DR)', 'Day trip diving.', 18.3667, -68.9667, 'Dominican', 'Dominican', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bayahibe', 'Dive resort town.', 18.3667, -68.844693, 'Dominican', 'Dominican', 'boat', 18, 18);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Anse Chastanet (St. Lucia)', 'Piton area diving.', 13.85229, -61.0667, 'St', 'St', 'shore', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Bianca C Wreck (Grenada)', 'Titanic of Caribbean.', 12.0333, -61.7550, 'Grenada', 'Grenada', 'boat', 40, 40);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Carlisle Bay (Barbados)', 'Multiple wrecks.', 13.0833, -59.6167, 'Barbados', 'Barbados', 'shore', 15, 15);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Buccoo Reef (Tobago)', 'Glass bottom boat area.', 11.1833, -60.8333, 'Trinidad', 'Trinidad', 'boat', 10, 10);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cocos Island - North', 'North side. World''s best hammerhead diving.', 5.5400, -87.0583, 'Cocos', 'Costa Rica', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cocos Island - South', 'South side. Manuelita Island nearby.', 5.5100, -87.0583, 'Cocos', 'Costa Rica', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cocos Island - East', 'East side. Dirty Rock.', 5.5250, -87.0433, 'Cocos', 'Costa Rica', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Cocos Island - West', 'West side. Bajo Alcyone.', 5.5250, -87.0733, 'Cocos', 'Costa Rica', 'boat', 35, 35);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Galapagos Santa Cruz - North', 'North side. Gordon Rocks nearby.', -0.4450, -90.3000, 'Galapagos', 'Ecuador', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Galapagos Santa Cruz - South', 'South side. Closer to town.', -0.7650, -90.3000, 'Galapagos', 'Ecuador', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Galapagos Santa Cruz - East', 'East side. Seymour Channel.', -0.7500, -90.2850, 'Galapagos', 'Ecuador', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Galapagos Santa Cruz - West', 'West side. Open ocean.', -0.744898, -90.309897, 'Galapagos', 'Ecuador', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Guadalupe Island - North', 'North side. Great white shark cage diving.', 29.1350, -118.2833, 'Guadalupe', 'Guadalupe', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Guadalupe Island - South', 'South side. More sheltered.', 28.8950, -118.2833, 'Guadalupe', 'Guadalupe', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Guadalupe Island - East', 'East side. Facing mainland.', 29.1000, -118.2683, 'Guadalupe', 'Guadalupe', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Guadalupe Island - West', 'West side. Open Pacific.', 29.1000, -118.3583, 'Guadalupe', 'Guadalupe', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Easter Island - North', 'North coast. Moai underwater.', -27.0517, -109.3500, 'Easter', 'Easter', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Easter Island - South', 'South coast. Rougher conditions.', -27.1717, -109.3500, 'Easter', 'Easter', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Easter Island - East', 'East coast. Less visited.', -27.1167, -109.1950, 'Easter', 'Easter', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Easter Island - West', 'West coast. Near town.', -27.1167, -109.4350, 'Easter', 'Easter', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pico Island - North', 'North coast. Blue shark diving.', 38.5117, -28.2500, 'Azores', 'Azores', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pico Island - South', 'South coast. Calmer conditions.', 38.3817, -28.2500, 'Azores', 'Azores', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pico Island - East', 'East coast. Princess Alice Bank.', 38.4667, -28.1850, 'Azores', 'Azores', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Pico Island - West', 'West coast. Faial channel.', 38.4667, -28.5550, 'Azores', 'Azores', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Hierro - North', 'North coast. Mar de las Calmas.', 27.7983, -18.0000, 'Canary', 'Canary', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Hierro - South', 'South coast. Marine reserve.', 27.6383, -18.0000, 'Canary', 'Canary', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Hierro - East', 'East coast. El Bajón.', 27.7333, -17.9150, 'Canary', 'Canary', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('El Hierro - West', 'West coast. Open Atlantic.', 27.7333, -18.2050, 'Canary', 'Canary', 'boat', 25, 25);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sal Island - North', 'North coast. Lemon sharks.', 16.8550, -22.9333, 'Cape', 'Cape', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sal Island - South', 'South coast. Santa Maria.', 16.5950, -22.9333, 'Cape', 'Cape', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sal Island - East', 'East coast. Trade wind side.', 16.7500, -22.8883, 'Cape', 'Cape', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sal Island - West', 'West coast. Calmer conditions.', 16.7500, -22.9883, 'Cape', 'Cape', 'boat', 20, 20);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sipadan - North', 'North side. Barracuda Point.', 4.1300, 118.6283, 'Sipadan', 'Sipadan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sipadan - South', 'South side. South Point.', 4.1000, 118.6283, 'Sipadan', 'Sipadan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sipadan - East', 'East side. Drop Off.', 4.1150, 118.6433, 'Sipadan', 'Sipadan', 'boat', 30, 30);

INSERT INTO spots (name, description, latitude, longitude, region, country, access_type, depth_min_m, depth_max_m)
VALUES ('Sipadan - West', 'West side. Turtle Cavern.', 4.1150, 118.6133, 'Sipadan', 'Sipadan', 'boat', 30, 30);

-- Total: 789 spots

-- ============================================
-- USER SPOTS TABLE
-- Stores user-created custom spots
-- ============================================
CREATE TABLE IF NOT EXISTS user_spots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    region VARCHAR(100) NOT NULL DEFAULT 'Custom',
    country VARCHAR(100) NOT NULL DEFAULT 'Custom',
    access_type VARCHAR(50) NOT NULL DEFAULT 'shore',
    created_at TIMESTAMP DEFAULT NOW(),
    
    -- Prevent same device from saving exact same location twice
    CONSTRAINT user_spots_unique_location UNIQUE(device_id, latitude, longitude)
);

-- Index for fast lookup by device
CREATE INDEX IF NOT EXISTS user_spots_device_idx ON user_spots(device_id);

-- Spatial index for nearby queries (uses PostGIS). Index expressions with a
-- cast need their own parentheses; without them psql aborts the whole
-- docker-entrypoint init and the container dies on first boot.
CREATE INDEX IF NOT EXISTS user_spots_location_idx ON user_spots USING GIST (
    ((ST_SetSRID(ST_MakePoint(longitude, latitude), 4326))::geography)
);

-- Index for name search
CREATE INDEX IF NOT EXISTS user_spots_name_idx ON user_spots(name);

-- ============================================
-- LEGAL ACCEPTANCE RECORDS
-- ============================================
-- Append-only record that a given install accepted the Terms/Privacy.
-- Authoritative (court-grade) proof of assent; the app also keeps a local
-- mirror for the first-launch gate. Keyed to the anonymous device id only —
-- no name/email/account, and intentionally NO IP or user-agent captured.
CREATE TABLE IF NOT EXISTS legal_acceptances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(64) NOT NULL,
    legal_version VARCHAR(32) NOT NULL,
    document VARCHAR(32) NOT NULL DEFAULT 'tos_privacy',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    app_version VARCHAR(32),
    platform VARCHAR(16)
);

-- Index for fast lookup of a device's latest acceptance
CREATE INDEX IF NOT EXISTS legal_acceptances_device_idx ON legal_acceptances(device_id);

-- ============================================
-- FISHING INTEL TABLES (ISOLATED)
-- SoCal fishing report aggregation
-- ============================================

-- Sources of fishing intel data
CREATE TABLE IF NOT EXISTS fishing_intel_sources (
    source_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    trust_tier CHAR(1) NOT NULL DEFAULT 'B',  -- A=landing, B=aggregator, C=other
    rate_limit_rps DECIMAL(3,1) DEFAULT 1.0,
    enabled BOOLEAN DEFAULT true,
    last_successful_fetch TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Raw HTML snapshots for debugging
CREATE TABLE IF NOT EXISTS fishing_intel_raw_pages (
    raw_page_id SERIAL PRIMARY KEY,
    source_id VARCHAR(50) REFERENCES fishing_intel_sources(source_id),
    url VARCHAR(512) NOT NULL,
    fetched_at TIMESTAMP NOT NULL DEFAULT NOW(),
    http_status INTEGER,
    etag VARCHAR(255),
    last_modified VARCHAR(255),
    html_blob TEXT,
    sha256 VARCHAR(64),
    CONSTRAINT fishing_intel_raw_pages_unique UNIQUE(url, fetched_at)
);

-- Parsed reports
CREATE TABLE IF NOT EXISTS fishing_intel_reports (
    report_id SERIAL PRIMARY KEY,
    source_id VARCHAR(50) REFERENCES fishing_intel_sources(source_id),
    url VARCHAR(512) NOT NULL,
    published_at TIMESTAMP,
    observed_at TIMESTAMP,
    report_type VARCHAR(30) NOT NULL,  -- FISH_COUNT, DOCK_TOTAL, NARRATIVE, BAIT, AUDIO_LINK, TRIP_ANNOUNCEMENT
    title VARCHAR(255),
    raw_excerpt TEXT,
    tldr TEXT,
    canonical_fingerprint VARCHAR(64),
    confidence DECIMAL(3,2) DEFAULT 1.0,
    thread_zone VARCHAR(50),
    content_type VARCHAR(30),
    last_activity_at TIMESTAMP,
    thread_url VARCHAR(512),
    region VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

-- Structured claims extracted from reports
CREATE TABLE IF NOT EXISTS fishing_intel_claims (
    claim_id SERIAL PRIMARY KEY,
    report_id INTEGER REFERENCES fishing_intel_reports(report_id) ON DELETE CASCADE,
    claim_type VARCHAR(30) NOT NULL,  -- CATCH, BAIT_AVAILABILITY, TARGETING, LOCATION_MENTION
    species VARCHAR(50),
    count_kept INTEGER,
    count_released INTEGER,
    bait_type VARCHAR(50),
    bait_status VARCHAR(50),
    trip_type VARCHAR(50),
    angler_count INTEGER,
    boat_name VARCHAR(100),
    landing_name VARCHAR(100),
    landing_city VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- SoCal landings with coordinates (gazetteer)
CREATE TABLE IF NOT EXISTS fishing_intel_landings (
    landing_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    normalized_name VARCHAR(100) NOT NULL,
    city VARCHAR(100),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    default_radius_km INTEGER DEFAULT 25
);

-- Geotags linking reports to locations
CREATE TABLE IF NOT EXISTS fishing_intel_report_geos (
    report_geo_id SERIAL PRIMARY KEY,
    report_id INTEGER REFERENCES fishing_intel_reports(report_id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    geo_type VARCHAR(30) NOT NULL,  -- LANDING_ANCHOR, PLACE_MENTION, REGION_FALLBACK
    radius_m INTEGER DEFAULT 25000
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS fishing_intel_reports_source_idx ON fishing_intel_reports(source_id);
CREATE INDEX IF NOT EXISTS fishing_intel_reports_fingerprint_idx ON fishing_intel_reports(canonical_fingerprint);
CREATE INDEX IF NOT EXISTS fishing_intel_reports_published_idx ON fishing_intel_reports(published_at DESC);
CREATE INDEX IF NOT EXISTS fishing_intel_reports_thread_url_idx ON fishing_intel_reports(thread_url);
CREATE INDEX IF NOT EXISTS fishing_intel_reports_region_idx ON fishing_intel_reports(region);
CREATE INDEX IF NOT EXISTS fishing_intel_claims_report_idx ON fishing_intel_claims(report_id);
CREATE INDEX IF NOT EXISTS fishing_intel_claims_species_idx ON fishing_intel_claims(species);
CREATE INDEX IF NOT EXISTS fishing_intel_report_geos_report_idx ON fishing_intel_report_geos(report_id);

-- Spatial index for nearby queries (uses PostGIS)
CREATE INDEX IF NOT EXISTS fishing_intel_report_geos_location_idx ON fishing_intel_report_geos USING GIST (
    ((ST_SetSRID(ST_MakePoint(longitude, latitude), 4326))::geography)
);

-- Seed initial sources
INSERT INTO fishing_intel_sources (source_id, name, base_url, trust_tier, rate_limit_rps) VALUES
    ('sportfishing-report', 'SportFishingReport.com', 'https://www.sportfishingreport.com', 'B', 1.0)
ON CONFLICT (source_id) DO NOTHING;

-- Seed SoCal landings
INSERT INTO fishing_intel_landings (name, normalized_name, city, latitude, longitude, default_radius_km) VALUES
    ('22nd Street Landing', '22nd_street', 'San Pedro', 33.7305, -118.2730, 30),
    ('Dana Wharf Sportfishing', 'dana_wharf', 'Dana Point', 33.4598, -117.6984, 25),
    ('Davey''s Locker', 'daveys_locker', 'Newport Beach', 33.6035, -117.9030, 25),
    ('Fisherman''s Landing', 'fishermans_landing', 'San Diego', 32.7235, -117.2260, 35),
    ('H&M Landing', 'hm_landing', 'San Diego', 32.7130, -117.2340, 35),
    ('Marina del Rey Sportfishing', 'marina_del_rey', 'Marina del Rey', 33.9744, -118.4493, 25),
    ('Newport Landing Sportfishing', 'newport_landing', 'Newport Beach', 33.6035, -117.9030, 25),
    ('Pierpoint Landing', 'pierpoint', 'Long Beach', 33.7605, -118.1965, 25),
    ('Point Loma Sportfishing', 'point_loma', 'San Diego', 32.7130, -117.2340, 35),
    ('Redondo Beach Sportfishing', 'redondo', 'Redondo Beach', 33.8425, -118.3920, 25),
    ('San Diego Sportfishing', 'sd_sportfishing', 'San Diego', 32.7235, -117.2260, 35),
    ('Santa Barbara Landing', 'santa_barbara', 'Santa Barbara', 34.4070, -119.6900, 30),
    ('Seaforth Sportfishing', 'seaforth', 'San Diego', 32.7650, -117.2295, 35),
    ('Ventura Sportfishing', 'ventura', 'Ventura', 34.2466, -119.2615, 30),
    ('Westport Landing', 'westport', 'San Diego', 32.7553, -117.2285, 35),
    ('Long Beach Sportfishing', 'long_beach', 'Long Beach', 33.7595, -118.1880, 25)
ON CONFLICT (name) DO NOTHING;
