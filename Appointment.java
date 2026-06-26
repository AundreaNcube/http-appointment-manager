public class Appointment
{
    private static int idCounter = 1;

    private int id;
    private String name;
    private String date_time;
    private String description;
    private byte[] photo;
    private String photoMimeType;

    public Appointment(String name, String date_time, String description) {
        this.id = idCounter++;
        this.name = name;
        this.date_time = date_time;
        this.description = description;
        this.photo = null;
        this.photoMimeType = null;
    }

    public Appointment(String name, String date_time, String description, byte[] photo, String mimeType) {
        this.id = idCounter++;
        this.name = name;
        this.date_time = date_time;
        this.description = description;
        this.photo = photo;
        this.photoMimeType = mimeType;
    }

    public int getId() { return id;}

    public String getName() { return name; }

    public String getDateTime() { return date_time; }

    public String getDescription() { return description; }

    public byte[] getPhoto() { return photo; }

    public String getPhotoMimeType() { return photoMimeType; }

    public boolean hasPhoto() {
        return photo != null && photo.length > 0;
    }

    @Override
    public String toString() {
        return "Appointment{" +
                "id=" + id +
                ", name='" + name +
                ", date_time='" + date_time  +
                ", description='" + description  +
                "  hasPhoto=" + hasPhoto() +
                '}';
    }
}