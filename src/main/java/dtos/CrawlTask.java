package dtos;

import java.net.URI;

/**
 * Crawl task representation.
 * 
 * @author cyrus
 */
public class CrawlTask {

	/**
	 * uri.
	 */
	public final URI uri;

	/**
	 * depth.
	 */
	public final int depth;

	/**
	 * クロール対象と深度の組。
	 *
	 * @param uri 対象URI
	 * @param depth 深度
	 */
	public CrawlTask(URI uri, int depth) {
		this.uri = uri;
		this.depth = depth;
	}
}