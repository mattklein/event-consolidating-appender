package org.mattklein.log4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Category;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.varia.DenyAllFilter;

public class EventConsolidatingAppender extends AppenderSkeleton {

    private final Logger logger = Logger.getLogger(this.getClass());

    private Appender downstreamAppender;
    private int delaySecs;
    // A per-logger cache of LoggingEvents.  Key is the name of the logger.
    private final ConcurrentHashMap<String, List<LoggingEvent>> cachedEvents;
    // A timer for each logger for which we're caching events.  Key is the name of the logger.
    private final ConcurrentHashMap<String, Timer> timers;
    
    public EventConsolidatingAppender() {
        this.cachedEvents = new ConcurrentHashMap<String, List<LoggingEvent>>();
        this.timers = new ConcurrentHashMap<String, Timer>();
    }
    
    public void setDownstreamAppender(String downstreamAppenderName) {
        Logger rootLogger = Logger.getRootLogger();
        Appender downstreamAppender = rootLogger.getAppender(downstreamAppenderName);
        if (downstreamAppender == null) {
            logger.error("Error configuring downstream appender -- couldn't obtain the appender named '" +
                    downstreamAppenderName + "' from the root logger");
        }
        else {
            // We don't want the downstream appender to receive logging events via the normal Log4J
            // mechanism; we only want it to receive the logging events that WE send to it.  So we add
            // the DenyAllFilter so that Log4J does not deliver logging events to it.
            downstreamAppender.addFilter(new DenyAllFilter());
            this.downstreamAppender = downstreamAppender;
        }
    }
    
    public void setDelaySecs(int delaySecs) {
        this.delaySecs = delaySecs;
    }
    
    @Override
    public boolean requiresLayout() {
        return false;
    }

    @Override
    protected void append(LoggingEvent event) {
        
        Category logger = event.getLogger();
        
        // We synchronize on the logger, since our cache is per-logger
        synchronized(logger) {
            
            final String loggerName = logger.getName();

            // Append the event to the cachedEvents for this logger
            List<LoggingEvent> existingEvents = cachedEvents.get(loggerName);
            if (existingEvents == null) {
                // Has to be a synchronizedList because we'll traverse and update it in another thread (the
                // timer thread)
                List<LoggingEvent> newList = Collections.synchronizedList(new ArrayList<LoggingEvent>());
                newList.add(event);
                cachedEvents.put(loggerName, newList);
            }
            else {
                existingEvents.add(event);
            }
            
            // If this is the first event we've cached for this logger, create a timer that fires after
            // the specified delay; after the delay, all of the cached events for this logger are consolidated
            // into a single event and this consolidated event is appended to the downstreamAppender.
            Timer existingTimer = timers.get(loggerName);
            if (existingTimer == null) {
                TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        consolidateEventsAndForward(loggerName);
                        timers.remove(loggerName);
                    }
                };
                Timer timer = new Timer();
                timer.schedule(task, delaySecs * 1000);
                timers.put(loggerName, timer);
            }
            else {
                // We've already scheduled the TimerTask for this logger; nothing to do
            }
        }
    }
    
    // Synchronized since this delivers events to the downstreamAppender -- and the downstreamAppender
    // is shared among ALL loggers
    private synchronized void consolidateEventsAndForward(String loggerName) {
        List<LoggingEvent> existingEvents = cachedEvents.remove(loggerName);
        LoggingEvent eventToSendDownstream;
        if (existingEvents.size() == 1) {
            eventToSendDownstream = existingEvents.get(0);
        }
        else {
            eventToSendDownstream = consolidatedEvent(existingEvents, downstreamAppender.getLayout());
        }
        // To deliver the consolidated event to the downstream appender, we temporarily
        // remove and then reinstate the DenyAllFilter
        downstreamAppender.clearFilters();
        downstreamAppender.doAppend(eventToSendDownstream);
        downstreamAppender.addFilter(new DenyAllFilter());
    }

    @Override
    public void close() {
        // Not sure when or how close() will get invoked -- it doesn't seem to be invoked when the
        // process is shut down.  But if it were to be invoked, this is what it should do -- clear
        // the cache of any cached events.
        // We're assuming that close() will only ever be called by one thread at a time.
        for (Map.Entry<String, List<LoggingEvent>> loggerEntry : cachedEvents.entrySet()) {
            consolidateEventsAndForward(loggerEntry.getKey());
            cachedEvents.remove(loggerEntry.getKey());
        }
    }

    /**
     * @param existingEvents
     * @return One new event that "consolidates" all of the passed events.  On this new event:
     * <ul><li>Priority is the <em>highest</em> priority of any of the events</li>
     * <li>Message is a concatenation of all of the events' messages</li></ul>
     */
    private LoggingEvent consolidatedEvent(List<LoggingEvent> existingEvents, Layout layout) {
        
        Level highestLevel = null;
        StringBuilder consolidatedMessage = new StringBuilder();
        String consolidatedFqnOfCategoryClass = null;
        Category consolidatedLogger = null;

        String linesep = System.getProperty("line.separator");
        if (existingEvents != null && existingEvents.size() > 0) {

            LoggingEvent firstEvent = existingEvents.get(0);
            
            highestLevel = Level.ALL;  // Level.ALL is the level with the lowest rank
            consolidatedMessage.append("The following " + existingEvents.size() +
                    " events were consolidated since they occurred within " + delaySecs +
                    " seconds of each other" + linesep);
            consolidatedFqnOfCategoryClass = firstEvent.fqnOfCategoryClass;
            consolidatedLogger = firstEvent.getLogger();
            int eventNum = 1;
            
            for (LoggingEvent event : existingEvents) {
                
                Level thisLevel = event.getLevel();
                if (thisLevel.isGreaterOrEqual(highestLevel)) {
                    highestLevel = thisLevel;
                }
                
                consolidatedMessage.append(linesep + "Event " + eventNum + ":" + linesep);
                consolidatedMessage.append(layout.format(event));
                
                if (!event.fqnOfCategoryClass.equals(consolidatedFqnOfCategoryClass)) {
                    // Shouldn't be possible
                    logger.warn("Unexpected result in logger event consolidation: category class '" + event.fqnOfCategoryClass +
                            "' is different than expected category class '" + consolidatedFqnOfCategoryClass +
                            "'.  Using '" + event.fqnOfCategoryClass + "' as the consolidated category class.");
                    consolidatedFqnOfCategoryClass = event.fqnOfCategoryClass;
                }
                if (event.getLogger() != consolidatedLogger) {
                    // Shouldn't be possible
                    logger.warn("Unexpected result in logger event consolidation: logger '" + event.getLogger() +
                            "' is different than expected logger '" + consolidatedLogger +
                            "'.  Using '" + event.getLogger() + "' as the consolidated logger.");
                    consolidatedLogger = event.getLogger();
                }
                eventNum++;
            }
        }

        return new LoggingEvent(consolidatedFqnOfCategoryClass,
                consolidatedLogger,
                highestLevel,
                consolidatedMessage.toString(),
                null);
    }
}
