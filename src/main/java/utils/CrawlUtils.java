package utils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;

/**
 * Crawl utils.
 * 
 * @author cyrus
 */
public class CrawlUtils {

	/**
	 * Wayback Machineのスナップショットセグメント.
	 */
	private static final Pattern WAYBACK_SNAPSHOT_SEGMENT = Pattern.compile("^(\\d{1,14})([A-Za-z_]+)?$");

	/**
	 * スキームに対応する既定ポートを返します。
	 *
	 * @param scheme スキーム（http/https）
	 * @return 既定ポート（不明な場合は-1）
	 */
	public static int defaultPortForScheme(String scheme) {
		if (scheme == null) {
			return -1;
		}
		if ("https".equalsIgnoreCase(scheme)) {
			return 443;
		}
		if ("http".equalsIgnoreCase(scheme)) {
			return 80;
		}
		return -1;
	}

	/**
	 * レスポンスがBASIC認証のチャレンジかどうかを判定します。
	 *
	 * @param response HTTPレスポンス
	 * @return BASIC認証チャレンジの場合はtrue
	 */
	public static boolean isBasicAuthChallenge(Connection.Response response) {
		if (response == null) {
			return false;
		}
		String header = response.header("WWW-Authenticate");
		if (header == null) {
			return false;
		}
		return Pattern.compile("(?i)\\bbasic\\b").matcher(header).find();
	}

	/**
	 * WWW-AuthenticateヘッダからBASIC認証のrealm値を抽出します。
	 *
	 * @param wwwAuthenticateHeader WWW-Authenticateヘッダ値
	 * @return realm（取得できない場合はnull）
	 */
	public static String parseBasicRealm(String wwwAuthenticateHeader) {
		if (wwwAuthenticateHeader == null) {
			return null;
		}
		Matcher m = Pattern.compile("(?i)realm=\"([^\"]*)\"").matcher(wwwAuthenticateHeader);
		if (m.find()) {
			return m.group(1);
		}
		m = Pattern.compile("(?i)realm=([^,\\s]+)").matcher(wwwAuthenticateHeader);
		if (m.find()) {
			return m.group(1);
		}
		return null;
	}

	/**
	 * URIのスキームがHTTP/HTTPSかどうかを判定します。
	 *
	 * @param uri 対象URI
	 * @return HTTP/HTTPSの場合はtrue
	 */
	public static boolean isHttp(URI uri) {
		String scheme = uri.getScheme();
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	/**
	 * 2つのURIが同一ホストかどうかを判定します。
	 *
	 * @param base 基準URI
	 * @param target 判定対象URI
	 * @return 同一ホストの場合はtrue
	 */
	public static boolean sameHost(URI base, URI target) {
		String baseHost = base.getHost();
		String targetHost = target.getHost();
		if (baseHost == null || targetHost == null) {
			return false;
		}
		return baseHost.equalsIgnoreCase(targetHost);
	}

	/**
	 * Wayback Machine（web.archive.org）のスナップショットURIをRAW（id_）モードに変換します。
	 *
	 * <p>例:</p>
	 * <pre>
	 * https://web.archive.org/web/20200101000000/http://example.com
	 *   => https://web.archive.org/web/20200101000000id_/http://example.com
	 * </pre>
	 *
	 * @param uri 変換対象URI
	 * @return 変換後URI（対象外の場合は元のURI）
	 */
	public static URI toInternetArchiveRaw(URI uri) {
		if (uri == null) {
			return null;
		}
		String host = uri.getHost();
		if (host == null || !"web.archive.org".equalsIgnoreCase(host)) {
			return uri;
		}
		String scheme = uri.getScheme();
		String authority = uri.getRawAuthority();
		String rawPath = uri.getRawPath();
		if (scheme == null || authority == null || rawPath == null) {
			return uri;
		}
		String prefix = "/web/";
		if (!rawPath.startsWith(prefix)) {
			return uri;
		}
		int segmentEnd = rawPath.indexOf('/', prefix.length());
		if (segmentEnd < 0) {
			return uri;
		}
		String segment = rawPath.substring(prefix.length(), segmentEnd);
		if (segment.contains("*")) {
			return uri;
		}
		Matcher matcher = WAYBACK_SNAPSHOT_SEGMENT.matcher(segment);
		if (!matcher.matches()) {
			return uri;
		}
		String timestamp = matcher.group(1);
		if ((timestamp + "id_").equals(segment)) {
			return uri;
		}
		String newPath = prefix + timestamp + "id_" + rawPath.substring(segmentEnd);
		StringBuilder sb = new StringBuilder();
		sb.append(scheme).append("://").append(authority).append(newPath);
		if (uri.getRawQuery() != null) {
			sb.append('?').append(uri.getRawQuery());
		}
		if (uri.getRawFragment() != null) {
			sb.append('#').append(uri.getRawFragment());
		}
		return URI.create(sb.toString());
	}

	/**
	 * ファイル名に拡張子が無い場合、Content-Typeから推定して付与します。
	 *
	 * @param fileName 元のファイル名
	 * @param contentType Content-Type
	 * @return 拡張子付与後のファイル名
	 */
	public static String appendExtensionIfMissing(String fileName, String contentType) {
		if (fileName.contains(".")) {
			return fileName;
		}
		return fileName + extensionFrom(contentType, ".bin");
	}

	/**
	 * Content-Typeから拡張子を推定します。
	 *
	 * @param contentType Content-Type
	 * @param fallback 判別できない場合のフォールバック（null可）
	 * @return 推定拡張子（例: ".html"）。判別できない場合はfallback（fallbackもnullなら空文字）
	 */
	public static String extensionFrom(String contentType, String fallback) {
		if (contentType == null) {
			return fallback != null ? fallback : "";
		}
		String type = contentType.split(";")[0].trim().toLowerCase(Locale.ENGLISH);
		if (type.contains("text/html")) {
			return ".html";
		}
		if (type.contains("text/css")) {
			return ".css";
		}
		if (type.contains("javascript") || type.contains("application/x-javascript")
				|| type.contains("application/ecmascript")) {
			return ".js";
		}
		if (type.contains("json")) {
			return ".json";
		}
		if (type.contains("xml")) {
			return ".xml";
		}
		if (type.contains("png")) {
			return ".png";
		}
		if (type.contains("jpeg") || type.contains("jpg")) {
			return ".jpg";
		}
		if (type.contains("gif")) {
			return ".gif";
		}
		if (type.contains("svg")) {
			return ".svg";
		}
		return fallback != null ? fallback : "";
	}

	/**
	 * ファイル名として安全な文字列に変換します。
	 *
	 * @param value 元の値
	 * @return 英数字と . _ - 以外を _ に置換した文字列
	 */
	public static String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	/**
	 * 文字列が最大長を超える場合、末尾をSHA-1ショートハッシュ（8文字）で置き換えて切り詰めます。
	 *
	 * <p>最大長以下の場合はそのまま返します。超過する場合は先頭 {@code (maxLength - 9)} 文字 + {@code "_"} +
	 * SHA-1ハッシュ上位8桁の16進文字列を返します。</p>
	 *
	 * @param value     切り詰め対象の文字列
	 * @param maxLength 最大文字数（9以上である必要があります）
	 * @return 切り詰め後の文字列
	 */
	public static String truncateSegment(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		String hash = sha1Short(value);
		int prefixLen = maxLength - 1 - hash.length(); // 1 for "_"
		if (prefixLen < 0) {
			prefixLen = 0;
		}
		return value.substring(0, prefixLen) + "_" + hash;
	}

	/**
	 * 文字列のSHA-1ハッシュ上位8桁の16進文字列を返します。
	 *
	 * @param value 入力文字列
	 * @return 8文字の16進ハッシュ
	 */
	private static String sha1Short(String value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(8);
			for (int i = 0; i < Math.min(4, digest.length); i++) {
				sb.append(String.format("%02x", digest[i] & 0xff));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-1 is always available in standard Java
			throw new IllegalStateException(e);
		}
	}
}