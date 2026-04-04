package dtos;

/**
 * Basic credentials.
 * 
 * @author cyrus
 */
public class BasicCredentials {

	/**
	 * username.
	 */
	public final String username;

	/**
	 * password.
	 */
	public final char[] password;

	/**
	 * BASIC認証の資格情報を生成します。
	 *
	 * @param username ユーザー名
	 * @param password パスワード（null可）
	 */
	public BasicCredentials(String username, char[] password) {
		this.username = username;
		this.password = password != null ? password : new char[0];
	}
}