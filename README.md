jHTTRack
=========================

## Website downloader written in Java
jHTTRack is a simple command-line tool written in Java that allows users to download entire websites for offline browsing. It recursively fetches HTML pages, images, stylesheets, and other resources linked within the specified website.

### How to run
1. Build: `mvn clean package`
2. Run: `java -cp target/jHttrack-0.0.1-SNAPSHOT.jar mains.Main`

Downloads are saved under `downloads/<host>/...` by default. Scope is limited to the same host and depth 3; adjust defaults in `src/main/java/constants/Configurations.java` if needed.

If the target site asks for BASIC authentication (HTTP 401 + `WWW-Authenticate: Basic`), jHttrack will prompt for username/password on the console and continue crawling.
