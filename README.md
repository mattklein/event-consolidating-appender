Log4J Event Consolidating Appender
==================================

This is a custom appender called **EventConsolidatingAppender**.  The purpose of this appender is to
consolidate multiple events that are received by a single logger within a specified number of seconds into a
single event; this single consolidated event is then forwarded to a "downstream" appender.

EventConsolidatingAppender is probably most useful when the downstream appender is SMTPAppender.  In this
case, you can use EventConsolidatingAppender so that if, say, 200 errors are raised to a given logger within
30 seconds, instead of receiving 200 emails, you'll receive one email that contains the messages from all 200
errors.

Example use case:  Our system makes high-volume, real-time requests to data providers' web services.  When we
have a problem contacting a provider's web service, we'll raise a warning or error event to a logger.  When a
data provider's service goes down for a several minutes, we may make multiple hundreds of requests to that
service that will all fail.  We like using SMTPAppender so that we receive immediate notification of any
warnings and errors via email, but if a data provider goes down for several minutes, and we're sending an email
per error, our email system may get overwhelmed or even disabled by our hosting provider.

Our goals could probably have been achieved via the configuration of more sophisticated operations/monitoring
software (Nagios, Zenoss, etc.), or via the delivery of events to a JMS queue, but in our case it wasn't
desirable to install, configure and maintain something so heavyweight.

(If only one event is received, it is delivered unmodified to the downstream appender.  This message is sent
to the downstream appender 30 seconds after it is raised, rather than immediately, but we consider this to be
a fine tradeoff.)

Sample log4j configuration:

    log4j.appender.ConsolidatingAppender=org.mattklein.log4j.EventConsolidatingAppender
    log4j.appender.ConsolidatingAppender.threshold=WARN
    log4j.appender.ConsolidatingAppender.downstreamAppender=EmailAppender
    log4j.appender.ConsolidatingAppender.delaySecs=30
    log4j.appender.EmailAppender=org.apache.log4j.net.SMTPAppender
    log4j.appender.EmailAppender.threshold=WARN
    log4j.appender.EmailAppender.layout=org.apache.log4j.PatternLayout
    log4j.appender.EmailAppender.layout.ConversionPattern=[%d{ISO8601}] [%t] [%c] [%l] %n%p -%m%n
    log4j.appender.EmailAppender.BufferSize=1
    log4j.appender.EmailAppender.SMTPHost=smtp.gmail.com
    log4j.appender.EmailAppender.SMTPUsername=you@yourdomain.com
    log4j.appender.EmailAppender.SMTPPassword=yourpassword
    log4j.appender.EmailAppender.From=Your Account <you@yourdomain.com>
    log4j.appender.EmailAppender.To=ops-announce@yourdomain.com
    log4j.appender.EmailAppender.Subject=[ALERT] Warning or error raised!

Sample "consolidated" event:

    [2011-02-21 10:55:15,901] [Timer-3] [com.yourcompany.yourapp.App] []
    ERROR - The following 6 events were consolidated since they occurred within 30 seconds of each other
    
    Event 1:
    [2011-02-21 10:54:45,896] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:77)]
    WARN - Warn message
    
    Event 2:
    [2011-02-21 10:54:45,898] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:78)]
    ERROR - Error message
    
    Event 3:
    [2011-02-21 10:54:57,903] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:77)]
    WARN - Warn message
    
    Event 4:
    [2011-02-21 10:54:57,903] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:78)]
    ERROR - Error message
    
    Event 5:
    [2011-02-21 10:55:09,905] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:77)]
    WARN - Warn message
    
    Event 6:
    [2011-02-21 10:55:09,905] [main] [com.yourcompany.yourapp.App] [com.yourcompany.yourapp.App.main(App.java:78)]
    ERROR - Error message
