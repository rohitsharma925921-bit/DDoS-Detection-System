import java.net.*;

public class AttackSimulator {

    public static void main(String[] args) {

        String target = "http://127.0.0.1:8080";

        //  50 threads attack
        for (int i = 0; i < 50; i++) {

            new Thread(() -> {
                while (true) {
                    try {
                        HttpURLConnection con =
                            (HttpURLConnection) new URL(target).openConnection();

                        con.setRequestMethod("GET");
                        con.getResponseCode();

                    } catch (Exception e) {
                        // ignore
                    }
                }
            }).start();
        }
    }
}
