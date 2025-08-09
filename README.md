# Vaadin + Quarkus (Undertow) — Local Network Device Scanner

A **Quarkus** + **Vaadin** web application that scans devices in a local network subnet, displays their IP address, hostname, ping time, and reachability, with real-time progress updates.

## ✨ Features

- 📡 **CIDR-based scanning** – Scan an entire subnet (e.g., `192.168.0.0/24`)
- 🖥 **Device details** – Shows IP address, hostname, ping time, and reachability
- 📊 **Real-time progress bar** – See scanning progress as a percentage
- 📑 **Sortable columns** – Sort devices by IP, hostname, reachability, or ping
- ⚡ **Responsive UI** – Built with Vaadin for smooth and interactive experience
- 🔄 **Asynchronous scanning** – UI remains responsive while scan runs in background

## 🛠 Tech Stack

- **Backend**: [Quarkus](https://quarkus.io/) (Java)
- **Frontend**: [Vaadin Flow](https://vaadin.com/flow) (Java-based UI)
- **HTTP Client**: Java `HttpClient`
- **JSON Parsing**: Jackson `ObjectMapper`

## Requirements
- JDK 17+
- Maven
- macOS/Linux/Windows (ICMP may require permissions / firewall allow)

## Run (IntelliJ)
1. File → New → Project from Existing Sources… → select `pom.xml`.
2. Wait for Quarkus and Vaadin to index.
3. Run:
   - Dev mode: `mvn quarkus:dev`
   - Or build & run: `mvn -DskipTests package && java -jar target/quarkus-app/quarkus-run.jar`
4. Open http://localhost:8080

## Endpoints
- UI: `/`
- API: `/api/scan?cidr=192.168.1.0/24&timeoutMs=150`

## Notes
- Servlet bootstrapping is handled via Undertow + the service file at `META-INF/services/jakarta.servlet.ServletContainerInitializer`.
- Only `/24` is supported for simplicity.
