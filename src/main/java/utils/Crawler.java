package utils;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	 * コンストラクタ.
	 *
	 * @param startUri
	 * @param outputRoot
	 * @param maxDepth
	 * @param sameHostOnly
	 * @param userAgent
	 * @param timeoutMillis
	 * @param overwrite
	 */
	public Crawler(URI startUri, Path outputRoot, int maxDepth, boolean sameHostOnly, String userAgent,
			int timeoutMillis, boolean overwrite) {
		this(startUri, outputRoot, maxDepth, sameHostOnly, userAgent, timeoutMillis, overwrite,
				new DefaultCredentialPrompter(null));
	}

	/**
	 * コンストラクタ.
	 *
	 * @param startUri
	 * @param outputRoot
	 * @param maxDepth
	 * @param sameHostOnly
	 * @param userAgent
	 * @param timeoutMillis
	 * @param overwrite
	 * @param credentialPrompter
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
	 * クロール処理.
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
	 * Fetch the content of the given URI.
	 *
	 * @param uri
	 * @return
	 * @throws IOException
	 */
	private static final int MAX_BASIC_AUTH_RETRIES = 3;

	private CrawlResult fetch(URI uri) throws IOException {
		return fetch(uri, 0);
	}

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

		if (status == 401 && isBasicAuthChallenge(response) && basicAuthAttempt < MAX_BASIC_AUTH_RETRIES) {
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
		if (status == 401 && isBasicAuthChallenge(response) && basicAuthAttempt < MAX_BASIC_AUTH_RETRIES) {
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

	private String resolvePreemptiveBasicAuth(URI uri) {
		AuthScope hostScope = AuthScope.forHost(uri, null);
		String hostAuth = basicAuthCache.get(hostScope);
		if (hostAuth != null) {
			return hostAuth;
		}
		return null;
	}

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

	private void clearBasicAuth(URI uri, String realm) {
		basicAuthCache.remove(AuthScope.forHost(uri, realm));
		basicAuthCache.remove(AuthScope.forHost(uri, null));
	}

	/**
	 * Save the fetched content to the local file system.
	 *
	 * @param uri
	 * @param result
	 * @throws IOException
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
	 * Enqueue links found in the document for further crawling.
	 *
	 * @param queue
	 * @param result
	 * @param nextDepth
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
	 * Normalize the given URL string to a URI.
	 *
	 * @param url
	 * @return
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
	 * Check if the URI uses HTTP or HTTPS scheme.
	 *
	 * @param uri
	 * @return
	 */
	private boolean isHttp(URI uri) {
		String scheme = uri.getScheme();
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	/**
	 * Check if two URIs have the same host.
	 *
	 * @param base
	 * @param target
	 * @return
	 */
	private boolean sameHost(URI base, URI target) {
		String baseHost = base.getHost();
		String targetHost = target.getHost();
		if (baseHost == null || targetHost == null) {
			return false;
		}
		return baseHost.equalsIgnoreCase(targetHost);
	}

	private static final Pattern WAYBACK_SNAPSHOT_SEGMENT = Pattern.compile("^(\\d{1,14})([A-Za-z_]+)?$");

	/**
	 * If the URI points to a Wayback Machine snapshot, rewrite it to RAW (id_) mode.
	 * Example:
	 * https://web.archive.org/web/20200101000000/http://example.com
	 *   => https://web.archive.org/web/20200101000000id_/http://example.com
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
	 * Convert a URI to a local file system path.
	 *
	 * @param uri
	 * @param contentType
	 * @return
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
	 * Append file extension based on content type if missing.
	 *
	 * @param fileName
	 * @param contentType
	 * @return
	 */
	private String appendExtensionIfMissing(String fileName, String contentType) {
		if (fileName.contains(".")) {
			return fileName;
		}
		return fileName + extensionFrom(contentType, ".bin");
	}

	/**
	 * Determine file extension from content type.
	 *
	 * @param contentType
	 * @param fallback
	 * @return
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
	 * Sanitize a string to be safe for file names.
	 *
	 * @param value
	 * @return
	 */
	private String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	/**
	 * Crawl task representation.
	 */
	private static class CrawlTask {
		final URI uri;
		final int depth;

		CrawlTask(URI uri, int depth) {
			this.uri = uri;
			this.depth = depth;
		}
	}

	/**
	 * Crawl result representation.
	 */
	private static class CrawlResult {
		final byte[] body;
		final String contentType;
		final Charset charset;
		final Document document;
		final boolean isHtml;

		CrawlResult(byte[] body, String contentType, Charset charset, Document document, boolean isHtml) {
			this.body = body;
			this.contentType = contentType;
			this.charset = charset;
			this.document = document;
			this.isHtml = isHtml;
		}
	}

	private static class AuthScope {
		final String scheme;
		final String host;
		final int port;
		final String realm;

		private AuthScope(String scheme, String host, int port, String realm) {
			this.scheme = scheme != null ? scheme.toLowerCase(Locale.ENGLISH) : null;
			this.host = host != null ? host.toLowerCase(Locale.ENGLISH) : null;
			this.port = port;
			this.realm = realm;
		}

		static AuthScope forHost(URI uri, String realm) {
			String scheme = uri != null ? uri.getScheme() : null;
			String host = uri != null ? uri.getHost() : null;
			int port = uri != null ? uri.getPort() : -1;
			if (port == -1) {
				port = defaultPortForScheme(scheme);
			}
			return new AuthScope(scheme, host, port, realm);
		}

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

		@Override
		public int hashCode() {
			return Objects.hash(scheme, host, port, realm);
		}
	}

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

	private static class BasicCredentials {
		final String username;
		final char[] password;

		BasicCredentials(String username, char[] password) {
			this.username = username;
			this.password = password != null ? password : new char[0];
		}
	}

	public interface CredentialPrompter {
		BasicCredentials promptBasic(URI uri, String realm);
	}

	public static class DefaultCredentialPrompter implements CredentialPrompter {
		private final Scanner scanner;

		public DefaultCredentialPrompter(Scanner scanner) {
			this.scanner = scanner;
		}

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