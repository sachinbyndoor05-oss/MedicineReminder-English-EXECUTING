CREATE DATABASE IF NOT EXISTS medicine_reminder;
USE medicine_reminder;
-- The Java application also creates these tables automatically.
-- Canonical user-name column is `name`; older databases using `full_name` are migrated at startup.
CREATE TABLE IF NOT EXISTS users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 email VARCHAR(160) UNIQUE NOT NULL,
 phone VARCHAR(30),
 password_hash VARCHAR(64) NOT NULL,
 photo MEDIUMTEXT,
 active TINYINT(1) NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS family_members (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 relation VARCHAR(50),
 photo MEDIUMTEXT,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS medicines (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 name VARCHAR(150) NOT NULL,
 name_en VARCHAR(150),
 name_hi VARCHAR(150),
 name_kn VARCHAR(150),
 dosage VARCHAR(100),
 time VARCHAR(5) NOT NULL,
 frequency VARCHAR(30),
 notes VARCHAR(500),
 ringtone VARCHAR(100),
 active TINYINT(1) NOT NULL DEFAULT 1,
 family_id BIGINT NULL,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY(family_id) REFERENCES family_members(id) ON DELETE SET NULL
);
CREATE TABLE IF NOT EXISTS dose_history (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 medicine_id BIGINT NOT NULL,
 taken_at DATETIME NOT NULL,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY(medicine_id) REFERENCES medicines(id) ON DELETE CASCADE
);
