package com.luge.medreminder

/** v1 → v2: per-medicine check-in window columns + global settings table (defaults 30/60). */
val MIGRATION_1_2_SQL = listOf(
    "ALTER TABLE `medicine` ADD COLUMN `winBefore` INTEGER DEFAULT NULL",
    "ALTER TABLE `medicine` ADD COLUMN `winAfter` INTEGER DEFAULT NULL",
    "CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    "INSERT OR IGNORE INTO `app_setting` (`key`, `value`) VALUES ('winBefore', 30), ('winAfter', 60)",
)

