import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AppointmentManager {
    private List<Appointment> appointments = new ArrayList<>();

    public void add(String name, String date_time, String description) {
        appointments.add(new Appointment(name, date_time, description));
    }

    public void add(String name, String date_time, String description, byte[] photo, String mimeType) {
        appointments.add(new Appointment(name, date_time, description, photo, mimeType));
    }

    public boolean delete(int id) {
        return appointments.removeIf(a -> a.getId() == id);
    }

    public Appointment findById(int id){
        return appointments.stream()
        .filter(a -> a.getId() == id)
        .findFirst()
        .orElse(null);
    }

    public List<Appointment> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(appointments);
        }

        String formatQuery = query.toLowerCase();
        return appointments.stream()
        .filter(a -> a.getName().toLowerCase().contains(formatQuery) || 
                        a.getDescription().toLowerCase().contains(formatQuery) ||
                        a.getDateTime().toLowerCase().contains(formatQuery))
        .collect(Collectors.toList());
    }

    public List<Appointment> getAll() {
        return new ArrayList<>(appointments);
    }
}