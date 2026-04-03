package mains;

import constants.Configurations;
import utils.Crawler;

import java.net.URI;
import java.util.Scanner;

/**
 * メイン処理.
 *
 * @author cyrus
 */
public class Main {

	/**
	 * main.
	 *
	 * @param args
	 */
	public static void main(String[] args) {
		try (Scanner scanner = new Scanner(System.in)) {
			System.out.print("Enter URL to crawl: ");
			String input = scanner.nextLine().trim();
			if (input.isEmpty()) {
				System.out.println("No URL provided. Exit.");
				return;
			}
			URI startUri = URI.create(input);
			Crawler crawler = new Crawler(startUri, Configurations.OUTPUT_ROOT, Configurations.MAX_DEPTH,
					Configurations.SAME_HOST_ONLY, Configurations.USER_AGENT, Configurations.TIMEOUT_MILLIS,
					Configurations.OVERWRITE, new Crawler.DefaultCredentialPrompter(scanner));
			crawler.crawl();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}