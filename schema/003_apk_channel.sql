-- APK release channels: phone (host) | wear (watch standalone)
-- Independent versionCode streams, activate latest per channel only

ALTER TABLE grokify_apk_releases
  ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'phone'
    AFTER version_name;

ALTER TABLE grokify_apk_releases
  DROP INDEX uq_grokify_apk_version_code;

ALTER TABLE grokify_apk_releases
  ADD UNIQUE KEY uq_grokify_apk_channel_version (channel, version_code);

ALTER TABLE grokify_apk_releases
  ADD KEY idx_grokify_apk_channel_active (channel, is_active, version_code);

INSERT IGNORE INTO schema_migrations (id) VALUES ('003_apk_channel');
