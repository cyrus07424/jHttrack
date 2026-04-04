package dtos;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

import utils.CrawlUtils;

/**
 * Auth scope.
 * 
 * @author cyrus
 */
public class AuthScope {

	/**
	 * scheme.
	 */
	public final String scheme;

	/**
	 * host.
	 */
	public final String host;

	/**
	 * port.
	 */
	public final int port;

	/**
	 * realm.
	 */
	public final String realm;

	/**
	 * BASIC認証のキャッシュキーとなるスコープを生成します。
	 *
	 * @param scheme スキーム（http/https）
	 * @param host ホスト
	 * @param port ポート（未指定の場合は既定ポートへ正規化済み）
	 * @param realm realm（null可）
	 */
	private AuthScope(String scheme, String host, int port, String realm) {
		this.scheme = scheme != null ? scheme.toLowerCase(Locale.ENGLISH) : null;
		this.host = host != null ? host.toLowerCase(Locale.ENGLISH) : null;
		this.port = port;
		this.realm = realm;
	}

	/**
	 * URIからホストスコープ（host/port + realm）を作成します。
	 *
	 * @param uri 対象URI
	 * @param realm realm（null可）
	 * @return 認証スコープ
	 */
	public static AuthScope forHost(URI uri, String realm) {
		String scheme = uri != null ? uri.getScheme() : null;
		String host = uri != null ? uri.getHost() : null;
		int port = uri != null ? uri.getPort() : -1;
		if (port == -1) {
			port = CrawlUtils.defaultPortForScheme(scheme);
		}
		return new AuthScope(scheme, host, port, realm);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AuthScope)) {
			return false;
		}
		AuthScope that = (AuthScope) o;
		return port == that.port && Objects.equals(scheme, that.scheme) && Objects.equals(host, that.host)
				&& Objects.equals(realm, that.realm);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		return Objects.hash(scheme, host, port, realm);
	}
}