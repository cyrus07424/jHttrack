package dtos;

import java.nio.charset.Charset;

import org.jsoup.nodes.Document;

/**
 * Crawl result representation.
 * 
 * @author cyrus
 */
public class CrawlResult {

	/**
	 * body.
	 */
	public final byte[] body;

	/**
	 * contentType.
	 */
	public final String contentType;

	/**
	 * charset.
	 */
	public final Charset charset;

	/**
	 * document.
	 */
	public final Document document;

	/**
	 * isHtml.
	 */
	public final boolean isHtml;

	/**
	 * 取得結果を生成します。
	 *
	 * @param body レスポンスボディ
	 * @param contentType Content-Type
	 * @param charset 文字コード（HTML解析に利用）
	 * @param document HTMLの場合の解析済みDocument（HTML以外はnull）
	 * @param isHtml HTMLかどうか
	 */
	public CrawlResult(byte[] body, String contentType, Charset charset, Document document, boolean isHtml) {
		this.body = body;
		this.contentType = contentType;
		this.charset = charset;
		this.document = document;
		this.isHtml = isHtml;
	}
}