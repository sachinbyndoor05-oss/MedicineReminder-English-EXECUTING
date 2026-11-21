# MediMate – Medicine Reminder

MediMate is a Java 17 + MySQL medicine reminder app. The interface is kept unchanged while the application includes compatibility fixes so existing databases can be upgraded without deleting user data.

## Features

1. Account registration with name, email, optional phone, and password validation.
2. Sign in and sign out with a secure server-side session cookie.
3. Case-insensitive, trimmed email handling for registration and login.
4. Existing-account detection with clear error messages.
5. Automatic creation of the `medicine_reminder` database on first run.
6. Automatic migration of older `users.full_name` schemas to the current `name` field.
7. Automatic repair/defaulting of legacy `users.active` values so new profiles can be created.
8. Automatic addition/repair of required legacy user, family, medicine, and history columns.
9. Existing data is preserved during schema migration; the app does not require deleting the database.
10. Multiple family profiles with name, relationship, and optional profile photo.
11. Family records are isolated by logged-in account.
12. Add, edit, and delete daily medicine schedules.
13. Medicine assignment to a valid family profile only.
14. Medicine dosage, time, notes, frequency, and ringtone settings.
15. English, Hindi, and Kannada medicine-name fields are supported by the database and reminder system.
16. Built-in reminder tones: Soft Chime, Digital Alert, and Gentle Bells.
17. Custom uploaded ringtone support (up to 5 MB) stored locally in IndexedDB.
18. Reminder overlay with a Turn Off action and optional browser notification.
19. Reminder notification language selection: English, Hindi, or Kannada.
20. Mark a medicine dose as taken from the dashboard.
21. Duplicate same-day dose protection.
22. Dose history stored in MySQL and displayed in the History page.
23. Dashboard statistics for medicines, family profiles, and completed doses.
24. Profile editing for name, phone, and profile photo.
25. Responsive interface preserved from the supplied design.
26. Input escaping in rendered medicine/family/history text to reduce HTML injection risks.
27. Clear API error messages and HTTP status handling.
28. Maven build support with Java 17 and MySQL Connector/J.

## Run in VS Code

1. Extract the ZIP and open the folder containing `pom.xml` in VS Code.
2. Start MySQL Server.
3. Set credentials if needed:
   - `MED_DB_USER` (default `root`)
   - `MED_DB_PASSWORD` (default `root`)
4. In the VS Code terminal run:

```powershell
mvn clean compile
mvn exec:java
```

5. Open http://localhost:8081

Do not delete an existing `medicine_reminder` database when upgrading. The application performs compatibility migrations at startup.


### Legacy database compatibility
The application automatically repairs common older schemas without deleting existing data, including `users.full_name`, `users.active`, medicine `active`, and legacy medicine `times`. Extra legacy NOT NULL columns without defaults are made nullable so they cannot block current profile, family, medicine, or dose-history operations.
