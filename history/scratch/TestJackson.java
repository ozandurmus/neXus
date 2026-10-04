import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

public class TestJackson {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree("");
            System.out.println("Root is null? " + (root == null));
            if (root != null) {
                System.out.println("Root class: " + root.getClass().getName());
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getClass().getName() + " - " + e.getMessage());
        }
    }
}
