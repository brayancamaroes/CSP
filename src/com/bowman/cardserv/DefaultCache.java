package com.bowman.cardserv;

import com.bowman.cardserv.interfaces.*;
import com.bowman.cardserv.util.*;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: bowman
 * Date: Oct 8, 2005
 * Time: 5:54:37 PM
 */
public class DefaultCache implements CacheHandler {

  protected ProxyLogger logger;
  protected MessageCacheMap pendingEcms;
  protected MessageCacheMap ecmMap;
  protected CacheListener listener, monitor;

  protected long maxAge;
  private long maxCacheWait;
  private int maxWaitPercent;

  private int timeouts, instantHits, waitHits, remoteHits, pendingPeak, contested;

  private Set caids = new HashSet();

  public DefaultCache() {
    logger = ProxyLogger.getLabeledLogger(getClass().getName());
  }

  public void configUpdated(ProxyXmlConfig xml) throws ConfigException {
    maxAge = xml.getTimeValue("cw-max-age", "s");
    String maxWaitStr = xml.getStringValue("max-cache-wait");
    if(maxWaitStr.endsWith("%")) {
      maxCacheWait = -1;
      maxWaitStr = maxWaitStr.substring(0, maxWaitStr.length() - 1);
      maxWaitPercent = Integer.parseInt(maxWaitStr);
      if(maxWaitPercent < 1 || maxWaitPercent > 100)
        throw new ConfigException(xml.getFullName(), "max-cache-wait", "Must be a time value or 1-100%");
    } else {
      maxWaitPercent = -1;
      maxCacheWait = xml.getTimeValue("max-cache-wait", "s");
    }
    if(pendingEcms == null) pendingEcms = new MessageCacheMap(maxAge);
    else pendingEcms.setMaxAge(maxAge);
    if(ecmMap == null) ecmMap = new MessageCacheMap(maxAge);
    else ecmMap.setMaxAge(maxAge);
    pendingPeak = 0;
  }

  public long getMaxCacheWait(long maxCwWait) {
    if(maxCacheWait == -1) return (long)((maxWaitPercent / 100.0) * maxCwWait);
    else return Math.min(maxCwWait, maxCacheWait);
  }

  public void start() {
    timeouts = 0;
    instantHits = 0;
    waitHits = 0;
    contested = 0;
  }

  public CamdNetMessage processRequest(int successFactor, CamdNetMessage request, boolean alwaysWait, long maxCwWait) {
    long start = System.currentTimeMillis();
    long maxWait = getMaxCacheWait(maxCwWait);

    synchronized(this) {
      
      boolean waited = false, alerted = false;
      long delay;

      if(!ecmMap.containsKey(request)) {

        if(alwaysWait) {
          // no cached or pending transaction but cache-only mode is set, so assume another proxy will provide cw
          if(!pendingEcms.containsKey(request)) addRequest(successFactor, request, alwaysWait);
        }

        if(listener != null) {
          // allow a set cache listener to introduce a lock for any other arbitrary reason
          if(!pendingEcms.containsKey(request) && listener.lockRequest(successFactor, request))
            addRequest(successFactor, request, true); // alwayswait = true <-- prevent clusteredcache from sending the lock
        }
      
        while(pendingEcms.containsKey(request)) {

          String origin = ((CamdNetMessage)pendingEcms.get(request)).getOriginAddress();

          if(origin != null && origin.equals(request.getRemoteAddress())) {
            // skip cache lock if the origin of the cache update is the same as that of the request
            break;
          }

          try {
            wait(500);
          } catch(InterruptedException e) {
            break;
          }
          delay = System.currentTimeMillis() - start;
          logger.finest("Waited for " + request.hashCodeStr() + " in cache: " + delay + " ms");
          waited = true;
          if(!alerted && delay >= (maxWait / 2)) {
            alerted = true;
            if(!ecmMap.containsKey(request)) delayAlert(successFactor, request, alwaysWait, maxWait);
          }
          if(delay >= maxWait) break;
        }
      }

      request.setCacheTime(request.getCacheTime() + (System.currentTimeMillis() - start));

      if(ecmMap.containsKey(request)) {
        CamdNetMessage reply = new CamdNetMessage((CamdNetMessage)ecmMap.get(request)); // return copy
        if(request.getServiceId() != 0) reply.setServiceId(request.getServiceId());
        if(waited) {
          waitHits++;
          reply.setInstant(false);
        } else {
          instantHits++;
          reply.setInstant(true);
        }
        if(reply.getOriginAddress() != null) remoteHits++;
        return reply;
      } else {

        if(waited) {
          pendingEcms.remove(request);
          timeouts++;
          request.setTimeOut(true);
          return null;
        }

        addRequest(successFactor, request, alwaysWait);
      }
      return null;
    }

  }

  protected void delayAlert(int successFactor, CamdNetMessage request, boolean alwaysWait, long maxWait) {
    // nothing to do in this impl
  }

  public synchronized boolean processReply(CamdNetMessage request, CamdNetMessage reply) {
    if(monitor != null) monitor.onReply(request, reply);
    if(reply == null || reply.isEmpty()) {
      removeRequest(request);
      notifyAll();
    } else {
      if(listener != null) listener.onReply(request, reply);
      if(pendingEcms.containsKey(request)) {
        addReply(request, reply);
        logger.finer("Reply received: " + request.hashCodeStr() + " -> " + reply.hashCodeStr() +
            " (pending: " + pendingEcms.size() + ")");
        removeRequest(request);
        notifyAll();
        return true;
      }
      addReply(request, reply);
    }
    return false;
  }

  public synchronized void processReplies(Map replies) {
    boolean lockFound = false;
    CamdNetMessage request, reply;
    for(Iterator iter = replies.keySet().iterator(); iter.hasNext(); ) {
      request = (CamdNetMessage)iter.next();
      reply = (CamdNetMessage)replies.get(request);
      // if(monitor != null) monitor.onReply(request, reply);
      if(listener != null) listener.onReply(request, reply);
      if(pendingEcms.containsKey(request)) {
        removeRequest(request);
        lockFound = true;
      }
      addReply(request, reply);
    }
    if(lockFound) notifyAll();
  }

  public CamdNetMessage peekReply(CamdNetMessage request) {
    return (CamdNetMessage)ecmMap.get(request);
  }

  public Properties getUsageStats() {
    Properties p = new Properties();
    p.setProperty("pending-ecms", String.valueOf(pendingEcms.size()) + " (peak: " + pendingPeak + ")");
    p.setProperty("cached-ecms", String.valueOf(ecmMap.size()));
    p.setProperty("timeouts", String.valueOf(timeouts));
    p.setProperty("instant-hits", String.valueOf(instantHits));
    p.setProperty("wait-hits", String.valueOf(waitHits));
    p.setProperty("contested", String.valueOf(contested));
    if(remoteHits > 0) p.setProperty("remote-hits", String.valueOf(remoteHits));    
    return p;
  }

  protected synchronized boolean contains(CamdNetMessage request) {
    return ecmMap.containsKey(request);
  }

  protected synchronized boolean containsPending(CamdNetMessage request) {
    return pendingEcms.containsKey(request);
  }

  public boolean containsCaid(int caid) {
    return caids.contains(new Integer(caid));
  }

  protected synchronized void addRequest(int successFactor, CamdNetMessage request, boolean alwaysWait) {
    CamdNetMessage oldRequest = (CamdNetMessage)pendingEcms.put(request, request);
    if(pendingEcms.size() > pendingPeak) pendingPeak = pendingEcms.size();
    if(oldRequest == null) {
      caids.add(new Integer(request.getCaId()));
      if(monitor != null) monitor.onRequest(successFactor, request);
      if(listener != null) listener.onRequest(successFactor, request);
    }
  }

  protected synchronized void addReply(CamdNetMessage request, CamdNetMessage reply) {
    if(reply.isEmpty()) return; // bad reply = unable to decode
    if(reply.getProfileName() == null) reply.setProfileName(request.getProfileName());
    if(reply.getNetworkId() == 0) reply.setNetworkId(request.getNetworkId());
    CamdNetMessage oldReply = (CamdNetMessage)ecmMap.put(request, reply);
    if(oldReply != null) {
      if(!oldReply.equals(reply)) {
        contested++;
        try {
          if(reply.addCandidate(oldReply)) // something is different, save the previous reply
            if(monitor != null) monitor.onContested(request, reply); // a new candidate was added, notify
        } catch (IllegalStateException e) {
          logger.warning("Could not retain cache reply for " + request.hashCodeStr() + ": " + e.getMessage());
        }
        if(!oldReply.equalsSingleDcw(reply)) { // completely different reply
          logger.warning("Contested cache reply for " + request.hashCodeStr() + ", both DCW differ - Previous: "
              + oldReply.toDebugString() + " Current: " + reply.toDebugString() + " (time difference: " +
              (reply.getTimeStamp() - oldReply.getTimeStamp()) + "ms)");
        } else { // one cw matches
          if(oldReply.hasZeroDcw() || reply.hasZeroDcw()) // one was zeroes only, probably harmless so avoid logging warning in this case
            logger.info("Contested cache reply for " + request.hashCodeStr() + ", different opposing DCW - Previous: " +
                oldReply.toDebugString() + " Current: " + reply.toDebugString());
          else logger.warning("Contested cache reply for " + request.hashCodeStr() + ", different opposing DCW - Previous: "
              + oldReply.toDebugString() + " Current: " + reply.toDebugString());
        }
      } else { // same reply, duplicate
        if(reply.getOriginAddress() != null) logger.fine("Duplicate cache reply for " + request.hashCodeStr() + " late by: " +
            (reply.getTimeStamp() - oldReply.getTimeStamp()) + " ms");
      }
    }
  }

  protected void removeRequest(CamdNetMessage request) {
    pendingEcms.remove(request);
  }

  public void setListener(CacheListener listener) {
    this.listener = listener;
  }

  public CacheListener getListener() {
    return listener;
  }

  public void setMonitor(CacheListener monitor) {
    this.monitor = monitor;
  }

  public CacheListener getMonitor() {
    return monitor;
  }

}
