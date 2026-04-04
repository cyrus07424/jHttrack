package crawlers;

import java.io.ByteArrayInputStream;
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
import java.util.Set;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import constants.Configurations;
import dtos.AuthScope;
import dtos.BasicCredentials;
import dtos.CrawlResult;
import dtos.CrawlTask;
import prompters.CredentialPrompter;
import prompters.DefaultCredentialPrompter;
import utils.CrawlUtils;

/**
 * Simple crawler that mirrors a site without altering server responses.
 *
 * @author cyrus
 */
public class Crawler {

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
		this.startUri = CrawlUtils.toInternetArchiveRaw(startUri);
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

		if (status == 401 && CrawlUtils.isBasicAuthChallenge(response)
				&& basicAuthAttempt < Configurations.MAX_BASIC_AUTH_RETRIES) {
			String realm = CrawlUtils.parseBasicRealm(response.header("WWW-Authenticate"));
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
		if (status == 401 && CrawlUtils.isBasicAuthChallenge(response)
				&& basicAuthAttempt < Configurations.MAX_BASIC_AUTH_RETRIES) {
			// Credentials likely wrong. Clear cached credentials for this scope and ask again.
			clearBasicAuth(uri, realm);
			String newRealm = CrawlUtils.parseBasicRealm(response.header("WWW-Authenticate"));
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
			if (!CrawlUtils.isHttp(candidate)) {
				continue;
			}
			if (sameHostOnly && !CrawlUtils.sameHost(startUri, candidate)) {
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
			uri = CrawlUtils.toInternetArchiveRaw(uri);
			return uri;
		} catch (URISyntaxException e) {
			return null;
		}
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
			safeSegments.add(CrawlUtils.sanitize(segment));
		}
		String fileName;
		if (path.endsWith("/")) {
			fileName = "index" + CrawlUtils.extensionFrom(contentType, null);
		} else {
			String last = safeSegments.isEmpty() ? "index" : safeSegments.remove(safeSegments.size() - 1);
			fileName = CrawlUtils.appendExtensionIfMissing(last, contentType);
		}
		String query = uri.getQuery();
		if (query != null && !query.isEmpty()) {
			fileName = fileName + "__" + CrawlUtils.sanitize(query);
		}
		Path resolved = outputRoot.resolve(host);
		for (String segment : safeSegments) {
			resolved = resolved.resolve(segment);
		}
		return resolved.resolve(fileName);
	}
}