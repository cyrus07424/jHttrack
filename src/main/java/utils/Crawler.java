package utils;

import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import constants.Configurations;

/**
 * Simple crawler that mirrors a site without altering server responses.
 *
 * @author cyrus
 */
public class Crawler {

	/**
	 * Wayback Machineのスナップショットセグメント.
	 */
	private static final Pattern WAYBACK_SNAPSHOT_SEGMENT = Pattern.compile("^(\\d{1,14})([A-Za-z_]+)?$");

	/**
	 * Starting URI.
	 */
	private final URI startUri;

	/**
	 * Output root directory.
	 */
	private final Path outputRoot;

	/**
	 * Maximum crawl depth.
	 */
	private final int maxDepth;

	/**
	 * Whether to crawl only the same host.
	 */
	private final boolean sameHostOnly;

	/**
	 * User-Agent string.
	 */
	private final String userAgent;

	/**
	 * Timeout in milliseconds.
	 */
	private final int timeoutMillis;

	/**
	 * Whether to overwrite existing files.
	 */
	private final boolean overwrite;

	/**
	 * Credential prompter used when a server asks for authentication.
	 */
	private final CredentialPrompter credentialPrompter;

	/**
	 * Cached Basic auth headers by scope (host/port + optional realm).
	 */
	private final Map<AuthScope, String> basicAuthCache = new HashMap<>();

	/**
	 * Set of visited URIs.
	 */
	private final Set<URI> visited = new HashSet<>();

	/**
	 * クローラを生成します。
	 *
	 * <p>開始URIがWayback Machine（web.archive.org）のスナップショットの場合は、取得対象URIを
	 * RAW（id_）モードへ正規化します（埋め込みリソースの取得を安定させるため）。</p>
	 *
	 * @param startUri 開始URI
	 * @param outputRoot 出力先ルートディレクトリ
	 * @param maxDepth クロール最大深度（開始URIが0）
	 * @param sameHostOnly 同一ホストのURIのみに限定する場合はtrue
	 * @param userAgent HTTPリクエストのUser-Agent
	 * @param timeoutMillis タイムアウト（ミリ秒）
	 * @param overwrite 既存ファイルを上書きする場合はtrue
	 */
	public Crawler(URI startUri, Path outputRoot, int maxDepth, boolean sameHostOnly, String userAgent,
			int timeoutMillis, boolean overwrite) {
		this(startUri, outputRoot, maxDepth, sameHostOnly, userAgent, timeoutMillis, overwrite,
				new DefaultCredentialPrompter(null));
	}

	/**
	 * クローラを生成します。
	 *
	 * @param startUri 開始URI
	 * @param outputRoot 出力先ルートディレクトリ
	 * @param maxDepth クロール最大深度（開始URIが0）
	 * @param sameHostOnly 同一ホストのURIのみに限定する場合はtrue
	 * @param userAgent HTTPリクエストのUser-Agent
	 * @param timeoutMillis タイムアウト（ミリ秒）
	 * @param overwrite 既存ファイルを上書きする場合はtrue
	 * @param credentialPrompter BASIC認証が要求された場合に資格情報を取得するためのプロンプタ（nullの場合はデフォルト）
	 */
	public Crawler(URI startUri, Path outputRoot, int maxDepth, boolean sameHostOnly, String userAgent,
			int timeoutMillis, boolean overwrite, CredentialPrompter credentialPrompter) {
		this.startUri = toInternetArchiveRaw(startUri);
		this.outputRoot = outputRoot;
		this.maxDepth = maxDepth;
		this.sameHostOnly = sameHostOnly;
		this.userAgent = userAgent;
		this.timeoutMillis = timeoutMillis;
		this.overwrite = overwrite;
		this.credentialPrompter = credentialPrompter != null ? credentialPrompter : new DefaultCredentialPrompter(null);
	}

	/**
	 * クロールを実行します。
	 *
	 * <p>開始URIから幅優先で辿り、取得できたレスポンスボディをローカルへ保存します。HTMLの場合は
	 * a/link/script/img/source/video/audio からリンク先を抽出し、深度上限までキューに追加します。</p>
	 */
	public void crawl() {
		Deque<CrawlTask> queue = new ArrayDeque<>();
		queue.add(new CrawlTask(startUri, 0));
		while (!queue.isEmpty()) {
			CrawlTask task = queue.removeFirst();
			URI uri = task.uri;
			if (visited.contains(uri)) {
				continue;
			}
			visited.add(uri);
			try {
				CrawlResult result = fetch(uri);
				save(uri, result);
				if (result.isHtml && task.depth < maxDepth) {
					enqueueLinks(queue, result, task.depth + 1);
				}
			} catch (IOException e) {
				System.err.println("Failed to fetch " + uri + " : " + e.getMessage());
			}
		}
	}

	/**
	 * 指定URIのコンテンツを取得します。
	 *
	 * <p>HTTP/HTTPSで接続し、ステータスコードが401でBASIC認証チャレンジの場合は
	 * 最大 {@value Configurations#MAX_BASIC_AUTH_RETRIES} 回まで再試行します。</p>
	 *
	 * @param uri 取得対象URI
	 * @return 取得結果（ボディ/Content-Type/文字コード/HTML解析結果）
	 * @throws IOException 取得に失敗した場合
	 */
	private CrawlResult fetch(URI uri) throws IOException {
		return fetch(uri, 0);
	}

	/**
	 * 指定URIのコンテンツを取得します（BASIC認証の再試行回数つき）。
	 *
	 * @param uri 取得対象URI
	 * @param basicAuthAttempt BASIC認証の試行回数（内部再帰用）
	 * @return 取得結果（ボディ/Content-Type/文字コード/HTML解析結果）
	 * @throws IOException 取得に失敗した場合
	 */
	private CrawlResult fetch(URI uri, int basicAuthAttempt) throws IOException {
		Connection conn = Jsoup.connect(uri.toString())
				.userAgent(userAgent)
				.timeout(timeoutMillis)
				.followRedirects(true)
				.ignoreContentType(true)
				.ignoreHttpErrors(true);

		String preemptiveAuth = resolvePreemptiveBasicAuth(uri);
		if (preemptiveAuth != null) {
			conn.header("Authorization", preemptiveAuth);
		}

		Connection.Response response = conn.execute();
		int status = response.statusCode();

		if (status == 401 && isBasicAuthChallenge(response)
				&& basicAuthAttempt < Configurations.MAX_BASIC_AUTH_RETRIES) {
			String realm = parseBasicRealm(response.header("WWW-Authenticate"));
			String authHeader = resolveOrPromptBasicAuth(uri, realm);
			if (authHeader == null) {
				throw new IOException("Basic auth cancelled for " + uri);
			}
			// Retry with provided credentials
			return fetchWithAuthorization(uri, authHeader, basicAuthAttempt + 1, realm);
		}

		if (status >= 400) {
			throw new IOException("HTTP " + status + " " + response.statusMessage() + " for " + uri);
		}

		byte[] body = response.bodyAsBytes();
		String contentTypeHeader = response.contentType();
		String contentType = contentTypeHeader != null ? contentTypeHeader : "";
		String charsetName = response.charset();
		Charset charset = charsetName != null ? Charset.forName(charsetName) : StandardCharsets.UTF_8;
		Document document = null;
		boolean isHtml = contentType.toLowerCase(Locale.ENGLISH).contains("text/html");
		if (isHtml) {
			try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
				document = Jsoup.parse(in, charset.name(), uri.toString());
			}
		}
		return new CrawlResult(body, contentType, charset, document, isHtml);
	}

	/**
	 * Authorizationヘッダを付与して指定URIを取得します（BASIC認証用）。
	 *
	 * <p>401(BASIC)が返った場合は、当該スコープのキャッシュをクリアして再プロンプトし、
	 * 規定回数まで再試行します。</p>
	 *
	 * @param uri 取得対象URI
	 * @param authorizationHeader 送信するAuthorizationヘッダ値（例: "Basic ..."）
	 * @param basicAuthAttempt BASIC認証の試行回数（内部再帰用）
	 * @param realm 認証レルム（WWW-Authenticateのrealm）
	 * @return 取得結果
	 * @throws IOException 取得に失敗した場合
	 */
	private CrawlResult fetchWithAuthorization(URI uri, String authorizationHeader, int basicAuthAttempt, String realm)
			throws IOException {
		Connection.Response response = Jsoup.connect(uri.toString())
				.userAgent(userAgent)
				.timeout(timeoutMillis)
				.followRedirects(true)
				.ignoreContentType(true)
				.ignoreHttpErrors(true)
				.header("Authorization", authorizationHeader)
				.execute();

		int status = response.statusCode();
		if (status == 401 && isBasicAuthChallenge(response)
				&& basicAuthAttempt < Configurations.MAX_BASIC_AUTH_RETRIES) {
			// Credentials likely wrong. Clear cached credentials for this scope and ask again.
			clearBasicAuth(uri, realm);
			String newRealm = parseBasicRealm(response.header("WWW-Authenticate"));
			String authHeader = resolveOrPromptBasicAuth(uri, newRealm);
			if (authHeader == null) {
				throw new IOException("Basic auth cancelled for " + uri);
			}
			return fetchWithAuthorization(uri, authHeader, basicAuthAttempt + 1, newRealm);
		}

		if (status >= 400) {
			throw new IOException("HTTP " + status + " " + response.statusMessage() + " for " + uri);
		}

		byte[] body = response.bodyAsBytes();
		String contentTypeHeader = response.contentType();
		String contentType = contentTypeHeader != null ? contentTypeHeader : "";
		String charsetName = response.charset();
		Charset charset = charsetName != null ? Charset.forName(charsetName) : StandardCharsets.UTF_8;
		Document document = null;
		boolean isHtml = contentType.toLowerCase(Locale.ENGLISH).contains("text/html");
		if (isHtml) {
			try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
				document = Jsoup.parse(in, charset.name(), uri.toString());
			}
		}
		return new CrawlResult(body, contentType, charset, document, isHtml);
	}

	/**
	 * レスポンスがBASIC認証のチャレンジかどうかを判定します。
	 *
	 * @param response HTTPレスポンス
	 * @return BASIC認証チャレンジの場合はtrue
	 */
	private boolean isBasicAuthChallenge(Connection.Response response) {
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
	private String parseBasicRealm(String wwwAuthenticateHeader) {
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
	 * 事前送信（preemptive）用のBASIC認証ヘッダをキャッシュから解決します。
	 *
	 * @param uri 対象URI
	 * @return Authorizationヘッダ値（ない場合はnull）
	 */
	private String resolvePreemptiveBasicAuth(URI uri) {
		AuthScope hostScope = AuthScope.forHost(uri, null);
		String hostAuth = basicAuthCache.get(hostScope);
		if (hostAuth != null) {
			return hostAuth;
		}
		return null;
	}

	/**
	 * BASIC認証ヘッダを解決します。
	 *
	 * <p>スコープ（host/port + realm）に紐づくキャッシュがあればそれを返し、
	 * なければ {@link CredentialPrompter} によりユーザーへ入力を促します。</p>
	 *
	 * @param uri 対象URI
	 * @param realm 認証レルム（null可）
	 * @return Authorizationヘッダ値（キャンセル/未入力の場合はnull）
	 */
	private String resolveOrPromptBasicAuth(URI uri, String realm) {
		AuthScope realmScope = AuthScope.forHost(uri, realm);
		String realmAuth = basicAuthCache.get(realmScope);
		if (realmAuth != null) {
			return realmAuth;
		}

		BasicCredentials creds = credentialPrompter.promptBasic(uri, realm);
		if (creds == null || creds.username == null || creds.username.trim().isEmpty()) {
			return null;
		}
		String authHeader;
		try {
			authHeader = "Basic " + Base64.getEncoder()
					.encodeToString((creds.username + ":" + new String(creds.password))
							.getBytes(StandardCharsets.ISO_8859_1));
		} finally {
			Arrays.fill(creds.password, '\0');
		}

		// Cache by realm (if present) and also by host so we can send it preemptively next time.
		basicAuthCache.put(realmScope, authHeader);
		basicAuthCache.put(AuthScope.forHost(uri, null), authHeader);
		return authHeader;
	}

	/**
	 * 指定スコープのBASIC認証キャッシュをクリアします。
	 *
	 * @param uri 対象URI
	 * @param realm 認証レルム（null可）
	 */
	private void clearBasicAuth(URI uri, String realm) {
		basicAuthCache.remove(AuthScope.forHost(uri, realm));
		basicAuthCache.remove(AuthScope.forHost(uri, null));
	}

	/**
	 * 取得したコンテンツをローカルファイルへ保存します。
	 *
	 * @param uri 元のURI
	 * @param result 取得結果
	 * @throws IOException 保存に失敗した場合
	 */
	private void save(URI uri, CrawlResult result) throws IOException {
		Path filePath = toPath(uri, result.contentType);
		Files.createDirectories(filePath.getParent());
		if (Files.exists(filePath) && !overwrite) {
			System.out.println("Skip existing " + filePath);
			return;
		}
		Files.write(filePath, result.body);
		System.out.println("Saved " + uri + " -> " + filePath);
	}

	/**
	 * HTMLからリンク先を抽出し、次のクロール対象としてキューへ追加します。
	 *
	 * @param queue 追加先キュー
	 * @param result 取得結果（HTMLのDocumentを含む想定）
	 * @param nextDepth 次に付与する深度
	 */
	private void enqueueLinks(Deque<CrawlTask> queue, CrawlResult result, int nextDepth) {
		if (result.document == null) {
			return;
		}
		List<String> targets = new ArrayList<>();
		Elements links = result.document
				.select("a[href], link[href], script[src], img[src], source[src], video[src], audio[src]");
		for (Element el : links) {
			String url = el.hasAttr("href") ? el.attr("abs:href") : el.attr("abs:src");
			targets.add(url);
		}
		for (String url : targets) {
			URI candidate = normalize(url);
			if (candidate == null) {
				continue;
			}
			if (!isHttp(candidate)) {
				continue;
			}
			if (sameHostOnly && !sameHost(startUri, candidate)) {
				continue;
			}
			if (visited.contains(candidate)) {
				continue;
			}
			queue.add(new CrawlTask(candidate, nextDepth));
		}
	}

	/**
	 * URL文字列を正規化してURIに変換します。
	 *
	 * <p>フラグメント（#...）は保存ファイル名や訪問判定の揺れを避けるため除去します。</p>
	 *
	 * @param url URL文字列
	 * @return 正規化済みURI（不正な場合はnull）
	 */
	private URI normalize(String url) {
		if (url == null || url.trim().isEmpty()) {
			return null;
		}
		try {
			URI uri = new URI(url.trim());
			if (uri.getFragment() != null) {
				uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
			}
			if (uri.getScheme() == null) {
				uri = startUri.resolve(uri);
			}
			uri = uri.normalize();
			uri = toInternetArchiveRaw(uri);
			return uri;
		} catch (URISyntaxException e) {
			return null;
		}
	}

	/**
	 * URIのスキームがHTTP/HTTPSかどうかを判定します。
	 *
	 * @param uri 対象URI
	 * @return HTTP/HTTPSの場合はtrue
	 */
	private boolean isHttp(URI uri) {
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
	private boolean sameHost(URI base, URI target) {
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
	private URI toInternetArchiveRaw(URI uri) {
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
	 * URIをローカルファイルシステム上のパスへ変換します。
	 *
	 * <p>host をルート配下の第1セグメントにし、path/query を安全なファイル名へ変換します。
	 * pathがディレクトリで終わる場合は index.* を採用します。</p>
	 *
	 * @param uri 対象URI
	 * @param contentType Content-Type（拡張子推定に利用）
	 * @return 保存先パス
	 */
	private Path toPath(URI uri, String contentType) {
		String host = uri.getHost() != null ? uri.getHost() : "unknown_host";
		String rawPath = uri.getPath();
		String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
		String[] segments = path.split("/");
		List<String> safeSegments = new ArrayList<>();
		for (String segment : segments) {
			if (segment.isEmpty()) {
				continue;
			}
			safeSegments.add(sanitize(segment));
		}
		String fileName;
		if (path.endsWith("/")) {
			fileName = "index" + extensionFrom(contentType, null);
		} else {
			String last = safeSegments.isEmpty() ? "index" : safeSegments.remove(safeSegments.size() - 1);
			fileName = appendExtensionIfMissing(last, contentType);
		}
		String query = uri.getQuery();
		if (query != null && !query.isEmpty()) {
			fileName = fileName + "__" + sanitize(query);
		}
		Path resolved = outputRoot.resolve(host);
		for (String segment : safeSegments) {
			resolved = resolved.resolve(segment);
		}
		return resolved.resolve(fileName);
	}

	/**
	 * ファイル名に拡張子が無い場合、Content-Typeから推定して付与します。
	 *
	 * @param fileName 元のファイル名
	 * @param contentType Content-Type
	 * @return 拡張子付与後のファイル名
	 */
	private String appendExtensionIfMissing(String fileName, String contentType) {
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
	private String extensionFrom(String contentType, String fallback) {
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
	private String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	/**
	 * スキームに対応する既定ポートを返します。
	 *
	 * @param scheme スキーム（http/https）
	 * @return 既定ポート（不明な場合は-1）
	 */
	private static int defaultPortForScheme(String scheme) {
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
	 * Crawl task representation.
	 */
	private static class CrawlTask {
		final URI uri;
		final int depth;

		/**
		 * クロール対象と深度の組。
		 *
		 * @param uri 対象URI
		 * @param depth 深度
		 */
		CrawlTask(URI uri, int depth) {
			this.uri = uri;
			this.depth = depth;
		}
	}

	/**
	 * Crawl result representation.
	 * 
	 * @author cyrus
	 */
	private static class CrawlResult {

		/**
		 * body.
		 */
		final byte[] body;

		/**
		 * contentType.
		 */
		final String contentType;

		/**
		 * charset.
		 */
		final Charset charset;

		/**
		 * document.
		 */
		final Document document;

		/**
		 * isHtml.
		 */
		final boolean isHtml;

		/**
		 * 取得結果を生成します。
		 *
		 * @param body レスポンスボディ
		 * @param contentType Content-Type
		 * @param charset 文字コード（HTML解析に利用）
		 * @param document HTMLの場合の解析済みDocument（HTML以外はnull）
		 * @param isHtml HTMLかどうか
		 */
		CrawlResult(byte[] body, String contentType, Charset charset, Document document, boolean isHtml) {
			this.body = body;
			this.contentType = contentType;
			this.charset = charset;
			this.document = document;
			this.isHtml = isHtml;
		}
	}

	/**
	 * Auth scope.
	 * 
	 * @author cyrus
	 */
	private static class AuthScope {

		/**
		 * scheme.
		 */
		final String scheme;

		/**
		 * host.
		 */
		final String host;

		/**
		 * port.
		 */
		final int port;

		/**
		 * realm.
		 */
		final String realm;

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
		static AuthScope forHost(URI uri, String realm) {
			String scheme = uri != null ? uri.getScheme() : null;
			String host = uri != null ? uri.getHost() : null;
			int port = uri != null ? uri.getPort() : -1;
			if (port == -1) {
				port = defaultPortForScheme(scheme);
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

	/**
	 * Basic credentials.
	 * 
	 * @author cyrus
	 */
	private static class BasicCredentials {

		/**
		 * username.
		 */
		final String username;

		/**
		 * password.
		 */
		final char[] password;

		/**
		 * BASIC認証の資格情報を生成します。
		 *
		 * @param username ユーザー名
		 * @param password パスワード（null可）
		 */
		BasicCredentials(String username, char[] password) {
			this.username = username;
			this.password = password != null ? password : new char[0];
		}
	}

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
		BasicCredentials promptBasic(URI uri, String realm);
	}

	/**
	 * Default credential prompter.
	 * 
	 * @author cyrus
	 */
	public static class DefaultCredentialPrompter implements CredentialPrompter {

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
}