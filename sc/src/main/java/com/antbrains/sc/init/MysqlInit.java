package com.antbrains.sc.init;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.antbrains.mqtool.ActiveMqSender;
import com.antbrains.mqtool.ActiveMqTools;
import com.antbrains.mqtool.HornetQTools;
import com.antbrains.mqtool.MqSender;
import com.antbrains.mqtool.MqToolsInterface;
import com.antbrains.mysqltool.PoolManager;
import com.antbrains.sc.archiver.Constants;
import com.antbrains.sc.archiver.MysqlArchiver;
import com.antbrains.sc.archiver.StatusAndUpdate;
import com.antbrains.sc.data.CrawlTask;
import com.antbrains.sc.data.WebPage;

public class MysqlInit {
	protected static Logger logger = Logger.getLogger(MysqlInit.class);
	
	private MqToolsInterface mqtools;
	private MqSender sender;
	MysqlArchiver archiver;
	private long initMinInterval;
	private static final long NO_TASK_INTERVAL=30*60_000;
	public MysqlInit(String[] args) throws Exception{
		initMinInterval=Integer.valueOf(args[3]);
		mqtools = new HornetQTools(args[0], args[1]);
		if (!mqtools.init()) {
			throw new IllegalArgumentException("can't connect to: " + args[0]);
		}
		sender = mqtools.getMqSender(args[2], ActiveMqSender.PERSISTENT);
		if (!sender.init(ActiveMqSender.PERSISTENT)) {
			throw new IllegalArgumentException("can't getMqSender: " + args[1]);
		}
		
		archiver = new MysqlArchiver();
		Map<String,StatusAndUpdate> status=archiver.getComponentStatus();
		if(status.size()>0){
			for(Entry<String,StatusAndUpdate> entry:status.entrySet()){
				logger.info("\t"+entry.getKey()+"\t"+entry.getValue().toString());
			}
			
			archiver.clearComponentStatus();
		}
		
		this.doInit();
	}
	private long lastInitTime;
	private long firstAllFinishTime;
	private boolean allFinish(Map<String,StatusAndUpdate> status){ 
		Map<String,List<String>> hosts=new HashMap<>();
		for(String key:status.keySet()){
			String[] arr=key.split("\t");
			List<String> port_keys=hosts.get(arr[0]);
			if(port_keys==null){
				port_keys=new ArrayList<>(2);
				hosts.put(arr[0], port_keys);
			}
			port_keys.add(arr[1]+"\t"+arr[2]);
		}
		for(Entry<String,List<String>> entry:hosts.entrySet()){
			boolean isHostGood=entry.getValue().size()==2;
			if(isHostGood){
				String[] arr1=entry.getValue().get(0).split("\t");
				String[] arr2=entry.getValue().get(1).split("\t");
				if((arr1[1].equals(Constants.COMPONENT_MSGRECV) && arr2[1].equals(Constants.COMPONENT_SCHEDULER))
						|| arr2[1].equals(Constants.COMPONENT_MSGRECV) && arr1[1].equals(Constants.COMPONENT_SCHEDULER)){
					
				}else{
					isHostGood=false;
				}
			}
			if(!isHostGood){
				StringBuilder sb=new StringBuilder();
				sb.append("port_keys of host bad: "+entry.getKey());
				for(String port_key:entry.getValue()){
					sb.append(" "+port_key);
				}
				logger.warn(sb.toString());
				return false;
			}
		} 
		
		for(Entry<String, StatusAndUpdate> entry:status.entrySet()){
			if(entry.getValue().status.equalsIgnoreCase(Constants.STATUS_HASTASK)){
				return false;
			}
		}
		logger.info("no task");
		for(Entry<String, StatusAndUpdate> entry:status.entrySet()){
			logger.info(entry.getKey()+"\t"+entry.getValue());
		}
		
		return true;
	}
	public void init(){
		firstAllFinishTime=-1;
		SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		while(true){
			try {
				Thread.sleep(Constants.UPDATE_COMPONENT_STATUS_INTERVAL);
			} catch (InterruptedException e) {
				
			} 
			Map<String,StatusAndUpdate> status=archiver.getComponentStatus();
			if(status.isEmpty()){
				logger.info("no status");
			}else{
				if(this.allFinish(status)){
					if(firstAllFinishTime==-1){
						firstAllFinishTime=System.currentTimeMillis();
						logger.info("firstAllFinishTime: "+firstAllFinishTime);
					}
				}else{
					firstAllFinishTime=-1;
				}
				if(firstAllFinishTime!=-1 && System.currentTimeMillis()-firstAllFinishTime>NO_TASK_INTERVAL){
					logger.info("ok to init: "+sdf.format(new Date(this.firstAllFinishTime)));
					if(System.currentTimeMillis()-this.lastInitTime>this.initMinInterval){
						this.doInit();
					}
				}
			}
			
			
		}
	}
	
	private void doInit(){
		logger.info("doInit");
		lastInitTime=System.currentTimeMillis(); 
		WebPage webPage = archiver.getWebPage(1);

		CrawlTask ct = new CrawlTask();
		ct.url = webPage.getUrl();
		ct.depth = webPage.getDepth();
		ct.failCount = 0;
		ct.priority = 0;
		ct.id = webPage.getId();
		ct.redirectedUrl = webPage.getRedirectedUrl();
		ct.lastVisit = 0;
		if (webPage.getLastVisitTime() != null) {
			ct.lastVisit = webPage.getLastVisitTime().getTime();
		}
		Gson gson = new Gson();
		String s = gson.toJson(ct);


		sender.send(s);
		
	}
	public static void main(String[] args) throws Exception {
		// put first page to queue
		if (args.length != 4) {
			logger.error("need 3 args conAddress jmxUrl dbName initInterval");
			System.exit(-1);
		}
		PoolManager.StartPool("conf", args[2]);
		MysqlInit init=new MysqlInit(args);
		init.init();
	}

}
