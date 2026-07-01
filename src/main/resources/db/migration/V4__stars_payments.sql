CREATE TABLE IF NOT EXISTS bot_user_payments (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT NOT NULL REFERENCES bot_users(telegram_id),
    telegram_charge_id VARCHAR(128) NOT NULL UNIQUE,
    stars_amount INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'completed',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bot_user_payments_telegram_id
    ON bot_user_payments (telegram_id);
