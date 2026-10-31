CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('BIDDER', 'SELLER', 'ADMIN')),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0)
);

CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY,
    seller_id INTEGER NOT NULL REFERENCES users(id),
    category TEXT NOT NULL CHECK (category IN ('ELECTRONICS', 'ART', 'VEHICLE')),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    starting_price_vnd INTEGER NOT NULL CHECK (starting_price_vnd >= 0),
    detail_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auctions (
    id INTEGER PRIMARY KEY,
    item_id INTEGER NOT NULL UNIQUE REFERENCES items(id),
    start_at_ms INTEGER NOT NULL CHECK (start_at_ms >= 0),
    end_at_ms INTEGER NOT NULL,
    current_price_vnd INTEGER NOT NULL CHECK (current_price_vnd >= 0),
    leader_user_id INTEGER REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'RUNNING', 'FINISHED', 'PAID', 'CANCELED')),
    CHECK (end_at_ms > start_at_ms)
);

CREATE TABLE IF NOT EXISTS bids (
    id INTEGER PRIMARY KEY,
    auction_id INTEGER NOT NULL REFERENCES auctions(id),
    bidder_user_id INTEGER NOT NULL REFERENCES users(id),
    amount_vnd INTEGER NOT NULL CHECK (amount_vnd > 0),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_items_seller ON items(seller_id);
CREATE INDEX IF NOT EXISTS idx_auctions_status_end ON auctions(status, end_at_ms);
CREATE INDEX IF NOT EXISTS idx_bids_auction_time ON bids(auction_id, created_at_ms, id);