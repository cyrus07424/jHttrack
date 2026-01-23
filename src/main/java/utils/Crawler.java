package utils;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
		this.startUri = startUri;
		this.outputRoot = outputRoot;
		this.maxDepth = maxDepth;
		this.sameHostOnly = sameHostOnly;
		this.userAgent = userAgent;
		this.timeoutMillis = timeoutMillis;
		this.overwrite = overwrite;
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
	private CrawlResult fetch(URI uri) throws IOException {
		Connection.Response response = Jsoup.connect(uri.toString())
				.userAgent(userAgent)
				.timeout(timeoutMillis)
				.followRedirects(true)
				.ignoreContentType(true)
				.execute();
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
			return uri.normalize();
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
}