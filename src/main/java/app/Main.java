package app;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by huuphuoc on 8/11/16.
 */
public class Main {
    public static void main(String[] args) {
        SoundDetector soundDetector = new SoundDetector();
        soundDetector.setOnSoundDetected(() -> {
            // TODO: 8/11/16
            System.out.print("Detected");
        });
        soundDetector.run();
//        soundDetector.stop();
    }
}
