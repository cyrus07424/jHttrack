package prompters;

import java.io.Console;
import java.net.URI;
import java.util.Scanner;

import dtos.BasicCredentials;

/**
 * Default credential prompter.
 * 
 * @author cyrus
 */
public class DefaultCredentialPrompter implements CredentialPrompter {

	/**
	 * scanner.
	 */
	private final Scanner scanner;

	/**
	 * デフォルトの資格情報プロンプタを生成します。
	 *
	 * @param scanner コンソールが使えない環境でのフォールバック入力（nullの場合はSystem.in）
	 */
	public DefaultCredentialPrompter(Scanner scanner) {
		this.scanner = scanner;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BasicCredentials promptBasic(URI uri, String realm) {
		String host = uri != null ? uri.getHost() : null;
		String realmPart = realm != null && !realm.isEmpty() ? " (realm: " + realm + ")" : "";
		System.out.println("BASIC認証が必要です: " + (host != null ? host : uri) + realmPart);

		Console console = System.console();
		if (console != null) {
			String user = console.readLine("Username: ");
			char[] pass = console.readPassword("Password: ");
			return new BasicCredentials(user, pass);
		}

		Scanner sc = this.scanner != null ? this.scanner : new Scanner(System.in);
		System.out.print("Username: ");
		String user = sc.nextLine();
		System.out.print("Password (will echo): ");
		String pass = sc.nextLine();
		return new BasicCredentials(user, pass != null ? pass.toCharArray() : new char[0]);
	}
}