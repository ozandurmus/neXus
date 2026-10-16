CREATE TABLE IF NOT EXISTS captures (
                id VARCHAR PRIMARY KEY, path VARCHAR, name VARCHAR, identity VARCHAR,
                state VARCHAR, checkpoint BIGINT, inventory VARCHAR, error VARCHAR)