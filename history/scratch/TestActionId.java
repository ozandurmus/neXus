import java.util.Map;

public class TestActionId {
    public static void main(String[] args) {
        Map<String, String> actionIdByRoute = Map.of(
            "POST /discovery/runs/*/import", "import_action"
        );
        String servletPath = "/discovery/runs/ABC123/import";
        String[] segments = servletPath.split("/", -1);
        for (int i = segments.length - 1; i >= 1; i--) {
            String[] wildcarded = segments.clone();
            wildcarded[i] = "*";
            String key = "POST " + String.join("/", wildcarded);
            System.out.println("Trying: " + key);
            if (actionIdByRoute.containsKey(key)) {
                System.out.println("Found: " + actionIdByRoute.get(key));
                return;
            }
        }
        System.out.println("Not found");
    }
}
