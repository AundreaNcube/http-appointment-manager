import java.io.*;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestHandler implements Runnable {
    private Socket clientSocket;
    private AppointmentManager manager;

    public RequestHandler(Socket clientSocket, AppointmentManager manager) {
        this.clientSocket = clientSocket;
        this.manager = manager;
    }

    @Override
    public void run() {
        try (
                InputStream rawIn = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream()) {
            String requestLine = readLine(rawIn); // Read the request line
            if (requestLine == null || requestLine.isEmpty())
                return;
            System.out.println("Request: " + requestLine);

            String[] parts = requestLine.split(" ");
            if (parts.length < 2)
                return;

            String method = parts[0].toUpperCase(); // GET or POST
            String path = parts[1];
            Map<String, String> headers = readHeaders(rawIn);

            if (!method.equals("GET") && !method.equals("POST")) {// Only GET and POST — 405 for anything else
                sendTextResponse(out, 405, "Method Not Allowed", "<h1>405 Method Not Allowed</h1>");
                return;
            }

            if (path.startsWith("/photo")) { // rotuing the image reuqests before html
                Map<String, String> params = parseQueryString(path);
                handlePhotoRequest(out, params);
                return;
            }

            Map<String, String> params;
            byte[] rawBody = null;
            String boundary = null;

            if (method.equals("POST")) {
                String contentType = headers.getOrDefault("content-type", "");

                if (contentType.contains("multipart/form-data")) {
                    boundary = extractBoundary(contentType);// Image upload uses multipart & extract bouindary token
                    int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    rawBody = readBytes(rawIn, contentLength);
                    params = parseMultipart(rawBody, boundary);
                } else {
                    int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    byte[] bodyBytes = readBytes(rawIn, contentLength);
                    String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);
                    params = parseQueryParams(bodyString);
                }
            } else {
                params = parseQueryString(path);
            }

            String action = params.getOrDefault("action", "");
            String message = "";
            int status = 200;

            switch (action) {

                case "add": {
                    String name = params.getOrDefault("name", "").trim();
                    String dateTime = params.getOrDefault("datetime", "").trim();
                    String description = params.getOrDefault("description", "").trim();

                    if (name.isEmpty() || dateTime.isEmpty()) {
                        status = 400;
                        message = "Error: Name and Date/Time are required fields.";
                    } else {
                        byte[] photoBytes = null;
                        String mimeType = null;

                        if (rawBody != null && boundary != null) {
                            photoBytes = extractFilePart(rawBody, boundary, "photo");
                            mimeType = extractFilePartMimeType(rawBody, boundary, "photo");
                        }

                        if (photoBytes != null && photoBytes.length > 0) {
                            manager.add(name, dateTime, description, photoBytes, mimeType);
                        } else {
                            manager.add(name, dateTime, description);
                        }
                        message = "Appointment for <strong>" + escapeHtml(name) + "</strong> added successfully.";
                    }
                    break;
                }

                case "delete": {
                    try {
                        int id = Integer.parseInt(params.getOrDefault("id", "-1"));
                        if (manager.delete(id)) {
                            message = "Appointment deleted successfully.";
                        } else {
                            status = 404;
                            message = "Error: No appointment found with that ID.";
                        }
                    } catch (NumberFormatException e) {
                        status = 400;
                        message = "Error: Invalid appointment ID.";
                    }
                    break;
                }

                case "search":
                    break;

                default:
                    break;
            }

            String searchQuery = params.getOrDefault("query", ""); // render page anf send
            List<Appointment> displayList = searchQuery.isEmpty()
                    ? manager.getAll()
                    : manager.search(searchQuery);

            String htmlBody = buildPage(displayList, message, searchQuery);
            sendTextResponse(out, status, statusText(status), htmlBody);

        } catch (IOException e) {
            System.err.println("Handler error: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handlePhotoRequest(OutputStream out, Map<String, String> params) throws IOException {
        try {
            int id = Integer.parseInt(params.getOrDefault("id", "-1"));
            Appointment appt = manager.findById(id);

            if (appt == null || !appt.hasPhoto()) {
                sendTextResponse(out, 404, "Not Found", "<h1>404 Photo Not Found</h1>");
                return;
            }

            byte[] photoBytes = appt.getPhoto();
            String mimeType = appt.getPhotoMimeType() != null ? appt.getPhotoMimeType() : "image/jpeg";

            // Send binary HTTP response — note we write raw bytes, NOT a String
            String responseHeaders = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + mimeType + "\r\n"
                    + "Content-Length: " + photoBytes.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
            out.write(photoBytes); // raw bytes, no encoding
            out.flush();

        } catch (NumberFormatException e) {
            sendTextResponse(out, 400, "Bad Request", "<h1>400 Bad Request</h1>");
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.mark(1);
                int next = in.read();
                if (next != '\n')
                    in.reset();
                break;
            }
            if (b == '\n')
                break;
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon != -1) {
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }
        return headers;
    }

    private byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1)
                break;
            offset += read;
        }
        return buffer;
    }

    private Map<String, String> parseQueryString(String path) {
        int queryStart = path.indexOf('?');
        if (queryStart == -1)
            return new HashMap<>();
        return parseQueryParams(path.substring(queryStart + 1));
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty())
            return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                    params.put(key, value);
                } catch (UnsupportedEncodingException ignored) {
                }
            }
        }
        return params;
    }

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length());
            }
        }
        return null;
    }

    private Map<String, String> parseMultipart(byte[] body, String boundary) {
        Map<String, String> params = new HashMap<>();
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        String[] sections = bodyStr.split(delimiter);

        for (String section : sections) {
            if (section.trim().isEmpty() || section.startsWith("--"))
                continue;

            int headerEnd = section.indexOf("\r\n\r\n");
            if (headerEnd == -1)
                continue;

            String sectionHeaders = section.substring(0, headerEnd);
            String value = section.substring(headerEnd + 4);

            if (value.endsWith("\r\n"))
                value = value.substring(0, value.length() - 2);

            if (!sectionHeaders.contains("Content-Type")) {
                String fieldName = extractDispositionField(sectionHeaders, "name");
                if (fieldName != null)
                    params.put(fieldName, value);
            }
        }

        params.put("action", "add"); //aaction comes from the form's action URL
        return params;
    }

    private byte[] extractFilePart(byte[] body, String boundary, String fieldName) {
        String delimiter = "--" + boundary;
        byte[] delimBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1);
        int pos = 0;

        while (pos < body.length) {
            int boundaryPos = indexOf(body, delimBytes, pos);
            if (boundaryPos == -1)
                break;

            int sectionStart = boundaryPos + delimBytes.length + 2; // skip \r\n after boundary
            if (sectionStart >= body.length)
                break;

            byte[] headerEndBytes = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            int contentStart = indexOf(body, headerEndBytes, sectionStart);
            if (contentStart == -1)
                break;

            String partHeaders = new String(body, sectionStart,
                    contentStart - sectionStart, StandardCharsets.ISO_8859_1);
            contentStart += 4; // skip the \r\n\r\n itself

            int nextBoundary = indexOf(body, delimBytes, contentStart);
            if (nextBoundary == -1)
                break;
            int contentEnd = nextBoundary - 2; // removee trailing \r\n before next boundary

            String nameField = extractDispositionField(partHeaders, "name");
            String filename = extractDispositionField(partHeaders, "filename");

            if (fieldName.equals(nameField) && filename != null && !filename.isEmpty()) {
                byte[] fileBytes = new byte[contentEnd - contentStart];
                System.arraycopy(body, contentStart, fileBytes, 0, fileBytes.length);
                return fileBytes;
            }

            pos = boundaryPos + delimBytes.length;
        }
        return null;
    }

    // Extract the MIME type declared in a named file upload part
    private String extractFilePartMimeType(byte[] body, String boundary, String fieldName) {// extrcat the MIME type
        String delimiter = "--" + boundary;
        byte[] delimBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1);
        int pos = 0;

        while (pos < body.length) {
            int boundaryPos = indexOf(body, delimBytes, pos);
            if (boundaryPos == -1)
                break;

            int sectionStart = boundaryPos + delimBytes.length + 2;
            if (sectionStart >= body.length)
                break;

            byte[] headerEndBytes = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            int contentStart = indexOf(body, headerEndBytes, sectionStart);
            if (contentStart == -1)
                break;

            String partHeaders = new String(body, sectionStart,
                    contentStart - sectionStart, StandardCharsets.ISO_8859_1);
            String nameField = extractDispositionField(partHeaders, "name");

            if (fieldName.equals(nameField)) {
                for (String line : partHeaders.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-type:")) {
                        return line.substring("content-type:".length()).trim();
                    }
                }
            }
            pos = boundaryPos + delimBytes.length;
        }
        return "image/jpeg";
    }

    private String extractDispositionField(String headers, String field) {
        for (String line : headers.split("\r\n")) {
            if (!line.toLowerCase().startsWith("content-disposition:"))
                continue;
            for (String part : line.split(";")) {
                part = part.trim();
                if (part.startsWith(field + "=")) {
                    return part.substring(field.length() + 1).replace("\"", "");
                }
            }
        }
        return null;
    }

    private int indexOf(byte[] pile, byte[] idx, int fromIndex) {
        outer: for (int i = fromIndex; i <= pile.length - idx.length; i++) {
            for (int j = 0; j < idx.length; j++) {
                if (pile[i + j] != idx[j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    private void sendTextResponse(OutputStream out, int statusCode, String statusText,
            String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            default:
                return "OK";
        }
    }

private String buildPage(List<Appointment> appointments, String message, String searchQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang='en'>\n<head>\n");
        sb.append("<meta charset='UTF-8'>\n");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        sb.append("<title>Appointment Manager</title>\n");
        sb.append("<style>\n").append(getStyles()).append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<div class='topbar'><h1>Appointment Manager</h1></div>\n");
        sb.append("<div class='content'>\n");
 
        if (!message.isEmpty()) {
            String cls = message.startsWith("Error") ? "message error" : "message";
            sb.append("<div class='").append(cls).append("'>").append(message).append("</div>\n");
        }
 
        sb.append("<div class='panel'>\n");
        sb.append("<div class='panel-header'>New Appointment</div>\n");
        sb.append("<div class='panel-body'>\n");
        sb.append("<form method='post' action='/?action=add' enctype='multipart/form-data'>\n");
        sb.append("<table class='form-table'>\n");
        sb.append("<tr><td>Name</td><td><input type='text' name='name' required></td></tr>\n");
        sb.append("<tr><td>Date &amp; Time</td><td><input type='datetime-local' name='datetime' required></td></tr>\n");
        sb.append("<tr><td>Description</td><td><input type='text' name='description' style='width:300px'></td></tr>\n");
        sb.append("<tr><td>Photo</td><td><input type='file' name='photo' accept='image/*'></td></tr>\n");
        sb.append("<tr><td></td><td style='padding-top:8px'><input type='submit' value='Add Appointment'></td></tr>\n");
        sb.append("</table>\n</form>\n");
        sb.append("</div>\n</div>\n");
 
        sb.append("<div class='panel'>\n");
        sb.append("<div class='panel-header'>Search</div>\n");
        sb.append("<div class='panel-body'>\n");
        sb.append("<form method='get' action='/'>\n");
        sb.append("<input type='hidden' name='action' value='search'>\n");
        sb.append("<div class='search-row'>\n");
        sb.append("<input type='text' name='query' placeholder='Enter search query - name, description or date' value='"
                + escapeHtml(searchQuery) + "'>\n");
        sb.append("<input type='submit' value='Search'>\n");
        if (!searchQuery.isEmpty()) {
            sb.append("<a href='/' class='btn btn-clear'>Clear</a>\n");
        }
        sb.append("</div>\n</form>\n");
        sb.append("</div>\n</div>\n");
 
        sb.append("<div class='panel'>\n");
        if (searchQuery.isEmpty()) {
            sb.append("<div class='panel-header'>All Appointments &nbsp;<span class='count'>(" + appointments.size() + ")</span></div>\n");
        } else {
            sb.append("<div class='panel-header'>Results for &ldquo;" + escapeHtml(searchQuery)
                    + "&rdquo; &nbsp;<span class='count'>(" + appointments.size() + ")</span></div>\n");
        }
        sb.append("<div class='panel-body' style='padding:0'>\n");
 
        if (appointments.isEmpty()) {
            sb.append("<p class='empty' style='padding:14px'>No appointments found.</p>\n");
        } else {
            sb.append("<table>\n");
            sb.append("<tr><th>Photo</th><th>#</th><th>Name</th><th>Date &amp; Time</th><th>Description</th><th></th></tr>\n");
            for (Appointment a : appointments) {
                sb.append("<tr>\n");
                sb.append("<td>");
                if (a.hasPhoto()) {
                    sb.append("<img src='/photo?id=").append(a.getId())
                      .append("' class='thumb' alt='photo of ").append(escapeHtml(a.getName())).append("'>");
                } else {
                    sb.append("<div class='no-photo'>&#128100;</div>");
                }
                sb.append("</td>\n");
                sb.append("<td>").append(a.getId()).append("</td>\n");
                sb.append("<td>").append(escapeHtml(a.getName())).append("</td>\n");
                sb.append("<td>").append(escapeHtml(a.getDateTime())).append("</td>\n");
                sb.append("<td>").append(escapeHtml(a.getDescription())).append("</td>\n");
                sb.append("<td><a href='/?action=delete&id=").append(a.getId())
                  .append("' class='btn btn-delete' onclick=\"return confirm('Delete this appointment?')\">Delete</a></td>\n");
                sb.append("</tr>\n");
            }
            sb.append("</table>\n");
        }
 
        sb.append("</div>\n</div>\n");
        sb.append("</div>\n</body>\n</html>");
        return sb.toString();
}

    private String getStyles() {
        return "* { box-sizing: border-box; margin: 0; padding: 0; }\n"
             + "body { font-family: 'Courier New', Courier, monospace; background: #1a1a2e; color: #e0e0e0; min-height: 100vh; }\n"
             + ".topbar { background: #16213e; border-bottom: 2px solid #0f3460; padding: 14px 28px; }\n"
             + ".topbar h1 { font-size: 1.2rem; color: #e94560; letter-spacing: 0.1em; text-transform: uppercase; }\n"
             + ".content { max-width: 960px; margin: 0 auto; padding: 24px 20px; }\n"
             + ".panel { background: #16213e; border: 1px solid #0f3460; margin-bottom: 18px; }\n"
             + ".panel-header { background: #0f3460; padding: 8px 14px; font-size: 0.75rem; color: #a0aec0; text-transform: uppercase; letter-spacing: 0.08em; }\n"
             + ".panel-body { padding: 16px 14px; }\n"
             + ".form-table { border-collapse: collapse; }\n"
             + ".form-table td { padding: 5px 10px 5px 0; font-size: 0.85rem; color: #a0aec0; vertical-align: middle; }\n"
             + ".form-table td:first-child { white-space: nowrap; }\n"
             + "input[type='text'], input[type='datetime-local'], input[type='file'] {\n"
             + "  background: #1a1a2e; border: 1px solid #0f3460; color: #e0e0e0;\n"
             + "  padding: 6px 10px; font-size: 0.85rem; font-family: inherit; width: 100%;\n"
             + "}\n"
             + "input[type='file'] { border: none; padding: 4px 0; }\n"
             + "input:focus { outline: none; border-color: #e94560; }\n"
             + "input[type='text'] { width: 260px; }\n"
             + "input[type='datetime-local'] { width: 220px; }\n"
             + ".search-row { display: flex; gap: 8px; align-items: center; }\n"
             + ".search-row input[type='text'] { width: 320px; }\n"
             + "input[type='submit'], .btn {\n"
             + "  background: #e94560; color: #fff; border: none; padding: 6px 16px;\n"
             + "  font-size: 0.8rem; font-family: inherit; text-transform: uppercase;\n"
             + "  letter-spacing: 0.06em; cursor: pointer; text-decoration: none; display: inline-block;\n"
             + "}\n"
             + "input[type='submit']:hover, .btn:hover { background: #c73652; }\n"
             + ".btn-clear { background: #0f3460; }\n"
             + ".btn-clear:hover { background: #1a4a8a; }\n"
             + ".btn-delete { background: transparent; border: 1px solid #e94560; color: #e94560; font-size: 0.75rem; padding: 3px 10px; }\n"
             + ".btn-delete:hover { background: #e94560; color: #fff; }\n"
             + ".message { padding: 8px 12px; margin-bottom: 14px; font-size: 0.85rem; border-left: 3px solid #38a169; background: #1a2e22; color: #9ae6b4; }\n"
             + ".message.error { border-left-color: #e94560; background: #2e1a1e; color: #fc8181; }\n"
             + "table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }\n"
             + "th { text-align: left; padding: 8px 10px; background: #0f3460; color: #a0aec0; font-weight: normal; text-transform: uppercase; letter-spacing: 0.06em; font-size: 0.75rem; }\n"
             + "td { padding: 9px 10px; border-bottom: 1px solid #0f3460; color: #e0e0e0; }\n"
             + "tr:hover td { background: #1e2d4a; }\n"
             + ".count { font-size: 0.75rem; color: #4a5568; }\n"
             + ".empty { color: #4a5568; font-style: italic; padding: 10px 0; font-size: 0.85rem; }\n"
             + ".thumb { width: 40px; height: 40px; object-fit: cover; border: 1px solid #0f3460; }\n"
             + ".no-photo { width: 40px; height: 40px; background: #0f3460; display: inline-flex; align-items: center; justify-content: center; color: #4a5568; font-size: 1rem; }\n";
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}