ALTER TABLE bot_users
    ADD COLUMN IF NOT EXISTS plan_expires_at TIMESTAMP(6);

UPDATE bot_users
SET plan_expires_at = CURRENT_TIMESTAMP + INTERVAL '30 days'
WHERE billing_plan = 'pro'
  AND plan_expires_at IS NULL;
