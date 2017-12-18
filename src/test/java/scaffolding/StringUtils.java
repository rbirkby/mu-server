package scaffolding;

import java.util.Random;

public class StringUtils {
	public static String randomString(int numberOfCharacters) {
		Random rng = new Random();
		StringBuilder sb = new StringBuilder(numberOfCharacters);
		for (int i = 0; i < numberOfCharacters; i++) {
			char c = (char) (rng.nextInt(89) + 33);
			sb.append(c);
		}
		return sb.toString();
	}
}