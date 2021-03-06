package com.antbrains.sc.extractor;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.antbrains.httpclientfetcher.HttpClientFetcher;
import com.antbrains.nekohtmlparser.NekoHtmlParser;
import com.antbrains.sc.archiver.Archiver;
import com.antbrains.sc.archiver.HbaseArchiver;
import com.antbrains.sc.data.Block;
import com.antbrains.sc.data.Link;
import com.antbrains.sc.data.WebPage;
import com.antbrains.sc.frontier.Frontier;

public abstract class UrlPatternExtractor4Hbase {
	protected static Logger logger = Logger.getLogger(UrlPatternExtractor4Hbase.class);
	protected Date startDate;

	public UrlPatternExtractor4Hbase() {
		startDate = new java.util.Date();
	}

	public abstract Extractor getExtractor(String url, String redirectUrl, int depth);

	protected int calcPriority(WebPage child) {
		return child.getDepth();
	}

	public void process(WebPage webPage, HttpClientFetcher fetcher, boolean followRedirect, Frontier frontier,
			HbaseArchiver archiver, String taskId) {
		String url = webPage.getUrl();
		if (url.startsWith("http://localhost/"))
			return;

		if (webPage.getRedirectedUrl() != null && followRedirect) {
			url = webPage.getRedirectedUrl();
		}
		Extractor extractor = this.getExtractor(url, webPage.getRedirectedUrl(), webPage.getDepth());

		if (extractor == null) {
			logger.warn("can't get extractor for " + url + " depth:" + webPage.getDepth() + " otherInfo: "
					+ webPage.getOtherInfo());
			return;
		} else if (extractor instanceof NullExtractor) {
			logger.info("nullext: " + url);
			return;
		} else {
			// logger.info("process: "+url);
		}

		boolean needUpdate = extractor.needUpdate(webPage);
		if (!needUpdate) {
			boolean needAddChildren = extractor.needAddChildren2FrontierIfNotUpdate(webPage);
			if (needAddChildren) {
				logger.info("addChildren: " + webPage.getUrl());
				archiver.loadBlocks(webPage);
				List<Block> existedBlocks = webPage.getBlocks();
				for (Block block : existedBlocks) {
					addChildren2Frontier(url, frontier, block, webPage.getDepth(), 1);
				}
			} else {
				logger.info("skipUpdate: " + webPage.getUrl());
			}

			return;
		}
		logger.info("process: " + url);
		String content = null;
		//
		Date lastVisit = archiver.getLastVisit(webPage.getUrl());
		webPage.setLastVisitTime(lastVisit);
		// ?????????????????????????????????????????????????????????????????????
		// ????????????????????????????????????????????????????????????
		// ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
		// ???BlockConfig????????? addChild2Frontier
		// ????????????????????????????????????????????????minDepth???maxDepth?????????????????????????????????????????????
		// ??????????????????????????????????????????????????????
		if (webPage.getLastVisitTime() != null && webPage.getLastVisitTime().getTime() >= this.startDate.getTime()) {
			logger.warn("LastVisitTime >= startTime: " + webPage.getUrl());
			if(webPage.getDepth()==0){
				logger.info("still proceeding: "+webPage.getDepth());
				this.startDate=new Date();
				logger.info("set startDate: "+this.startDate.toString());
			}else{
				return;
			}
		}

		try {
			// content = fetcher.httpGet(url);
			String[] arr = fetcher.httpGetReturnRedirect(url, 3);
			if (arr == null) {
				content = null;
			} else {
				content = arr[0];
				if (webPage.getUrl().equals(arr[1])) {
					arr[1] = null;
				}
				webPage.setRedirectedUrl(arr[1]);
			}
			String redirectedUrl = webPage.getRedirectedUrl();
			if (redirectedUrl != null && !redirectedUrl.equals(webPage.getUrl())) {
				extractor = this.getExtractor(url, redirectedUrl, webPage.getDepth());
				if (extractor == null) {
					logger.warn("can't get extractor for " + url + " depth:" + webPage.getDepth() + " otherInfo: "
							+ webPage.getOtherInfo());
					return;
				} else if (extractor instanceof NullExtractor) {
					logger.info("nullext: " + url);
					return;
				} else {
					// logger.info("process: "+url);
				}
			}
		} catch (Exception e) {

		}
		if (content == null) {
			logger.error("can't get: " + url);
			archiver.saveUnFinishedWebPage(webPage, 1);
			return;
		}
		boolean saveHtml = extractor.needSaveHtml(webPage);
		if (saveHtml) {
			webPage.setContent(content);
		}
		NekoHtmlParser parser = new NekoHtmlParser();
		try {
			parser.load(content, "UTF8");
		} catch (Exception e) {
			logger.error("can't parse: " + url);
			return;
		}
		extractor.extractBasicInfo(webPage, content, archiver, taskId);
		extractor.extractProps(webPage, parser, fetcher, content, archiver, taskId);
		webPage.setLastVisitTime(new Date());
		webPage.setCrawlPriority(this.calcPriority(webPage));

		archiver.updateWebPage(webPage);
		List<Block> blocks = extractor.extractBlock(webPage, parser, fetcher, content, archiver, taskId);
		if (blocks != null) {
			this.processBlocks(webPage, blocks, url, frontier, archiver);
		}

	}

	private void processBlocks(WebPage webPage, List<Block> extractedBlocks, String url, Frontier frontier,
			HbaseArchiver archiver) {

		for (int i = 0; i < extractedBlocks.size(); i++) {
			Block extractedBlock = extractedBlocks.get(i);
			archiver.insert2Block(extractedBlock, webPage, i);
			// ????????????????????????????????mysql????????????id
			// for(Link link:extractedBlock.getLinks()){
			// WebPage child=link.getWebPage();
			// archiver.updateWebPage(child, false);
			// }
			if (this.needAddChildren2Frontier(extractedBlock)) {
				for (Link link : extractedBlock.getLinks()) {
					WebPage child = link.getWebPage();
					Extractor ext = this.getExtractor(child.getUrl(), child.getRedirectedUrl(), child.getDepth());
					if (ext != null && !ext.needUpdate(child) && !ext.needAddChildren2FrontierIfNotUpdate(child)) {
						logger.info("skipAddChild2Frontier: " + child.getUrl());
					} else {
						child.setCrawlPriority(this.calcPriority(child));
						frontier.addWebPage(child, 0);
					}
				}
			}
		}

	}

	private boolean needAddChildren2Frontier(Block extractedBlock) {
		boolean addChild2Frontier = true;
		if (extractedBlock != null) {
			Map<String, Object> otherInfo = extractedBlock.getOtherInfo();
			if (otherInfo != null) {
				Boolean b = (Boolean) otherInfo.get(Block.OTHER_INFO_ADD_CHILD);
				if (b != null && b.booleanValue() == false) {
					addChild2Frontier = false;
				}
			}
		}

		return addChild2Frontier;
	}

	public static final String PARENT_URL = "parent_url";

	private void addChildren2Frontier(String pUrl, Frontier frontier, Block block, int parentLevel, int childPriority) {
		if (block == null)
			return;
		if (block.getLinks() != null) {
			for (Link link : block.getLinks()) {
				WebPage page = link.getWebPage();
				page.getOtherInfo().put(PARENT_URL, pUrl);
				page.setDepth(parentLevel + 1);
				page.setCrawlPriority(this.calcPriority(page));
				frontier.addWebPage(page, 0);
			}
		}
	}

}
