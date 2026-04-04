package prompters;

import java.net.URI;

import dtos.BasicCredentials;

/**
 * BASIC認証が必要な場合に、資格情報を取得するためのコールバック。
 * 
 * @author cyrus
 */
public interface CredentialPrompter {

	/**
	 * BASIC認証用のユーザー名/パスワードを取得します。
	 *
	 * @param uri 対象URI
	 * @param realm realm（null可）
	 * @return 資格情報（キャンセルしたい場合はnull）
	 */
	public BasicCredentials promptBasic(URI uri, String realm);
}