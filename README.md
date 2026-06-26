# COS332 - PRACTICAL ASSIGNMENT 4

## INTRODUCTION

An HTTP server in Java that serves browser-based appointment manager that allows for adding, deleting and searching of appointments. After startign server, all the interactions happer through the browser.

## File Structure
 
| File | Responsibility |
|---|---|
| `Main.java` | Starts the server on port 8080. |
| `Server.java` | Opens a `ServerSocket`, accepts connections and a new thread is created per client. |
| `RequestHandler.java` | Parses the raw HTTP request, extracts parameters, routes actions, creates and sends the HTTP responses. |
| `AppointmentManager.java` | Appointment storage. Provides `add()`, `delete()`, `search()`, and `getAll()`. |
| `Appointment.java` | Data class storing ID, name, date/time, description, and optional photo. |
 
---

## Compiling and Running
 
### 1. Compile
 
Compile all files at once:
 
```bash
javac *.java
```
 
### 2. Run
 
```bash
java Main
```

You will see:
 
```
Starting Appointment Server on port 8080
Open your browser and go to: http://localhost:8080
Server is running. Waiting for connections...
```
 
### 3. Open in browser
 
Open Chrome, Firefox, or any browser and go to:
 
```
http://localhost:8080
```

### 4. Stopping the server
 
Press `Ctrl + C` in the terminal.
 
> **Note:** Appointments are stored in memory only. All data is lost when the server stops.
 
---

# Features
 
### Add an Appointment
Fill in Name, Date & Time, and optionally a Description and Photo, then click Add Appointment. The form submits via HTTP POST to the server, which stores the appointment and reloads the page with the new entry in the table.
 
### Search Appointments
Type any keyword into the search bar and click Search. The server filters by name, description, or date/time. Click Clear to return to the full list.
 
### Delete an Appointment
Click Delete on any row and confirm the modal prompt. The server removes the entry and reloads the page.
 
---

## Bonus Features Implemented
 
### 1. POST Method Support
 
The add form uses `method="post"` with `enctype="multipart/form-data"`. Rather than parameters appearing in the URL, the form data is sent in the HTTP request body. The server reads the `Content-Length` header to know exactly how many bytes to read from the socket, then parses the body.
 
The search form intentionally stays as `GET`, GET for retrieval, POST for actions that change server state.
 
### 2. Image Serving over HTTP
 
A photo can be uploaded with any appointment. When the browser loads the appointments table, it encounters `<img src="/photo?id=1">` and automatically submits a second HTTP request to the server. The server handles this by:
 
- Looking up the appointment by ID
- Reading the stored raw image bytes
- Sending back a binary HTTP response with the correct `Content-Type` header (e.g. `image/jpeg`, `image/png`)
 
The response headers are sent as UTF-8 text, followed immediately by the raw binary image bytes — one after the other so the image will not be corrupted.
 
### 3. HTTP Status Codes (RFC 2616)
 
The server returns correct status codes rather than always returning 200:
 
| Scenario | Status Code |
|---|---|
| Successful page load or action | `200 OK` |
| Required fields missing on add | `400 Bad Request` |
| Deleting an ID that does not exist | `404 Not Found` |
| Request method is not GET or POST | `405 Method Not Allowed` |
 
Error messages render in a red banner on the page. The status code is visible in the HTTP response line itself (testable with curl).
 
---

## Testing the Status Codes with curl
 
Run these commands while the server is running to verify the status codes directly:
 
**400 — missing required fields:**
```bash
curl -v "http://localhost:8080/?action=add&name=&datetime="
```
 
**404 — deleting a non-existent ID:**
```bash
curl -v "http://localhost:8080/?action=delete&id=9999"
```
 
**405 — unsupported HTTP method:**
```bash
curl -v -X DELETE "http://localhost:8080/"
curl -v -X PUT "http://localhost:8080/"
```
 
**200 — normal page load:**
```bash
curl -v "http://localhost:8080/"
```

## HTTP Implementation Notes
 
The server handles HTTP manually, demonstrating understanding of RFC 2616:
 
- Every response includes a correct status line: `HTTP/1.1 200 OK`
- `Content-Type` is always set, e.g. `text/html; charset=UTF-8` or `image/jpeg`
- `Content-Length` is calculated and sent in every response
- `Connection: close` signals the client that the connection will close after the response

---