package cloudsim.ext.stat;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Enhanced HourlyEventCounter with sliding window and rate calculation capabilities
 */
public class HourlyEventCounter {
    private final String name;
    private final long[] hourlyCount = new long[24];
    private final Queue<EventRecord> eventWindow = new LinkedList<>();
    private double windowSizeMs = 3600000; // 1 hour window
    
    // For sliding window calculations
    private static class EventRecord {
        final double timestamp;
        final int count;
        
        EventRecord(double timestamp, int count) {
            this.timestamp = timestamp;
            this.count = count;
        }
    }

    public HourlyEventCounter(String name) {
        this.name = name;
    }

    public void addEvent(double timeInMS, int countAs) {
        // Maintain original hourly counting
        int timeInHrs = (int) Math.floor((timeInMS / (1000 * 60 * 60)) % 24);
        if (timeInHrs < 24) {
            hourlyCount[timeInHrs] += countAs;
        }
        
        // Add to sliding window
        eventWindow.add(new EventRecord(timeInMS, countAs));
        cleanupOldEvents(timeInMS);
    }

    private void cleanupOldEvents(double currentTime) {
        while (!eventWindow.isEmpty() && 
              (currentTime - eventWindow.peek().timestamp) > windowSizeMs) {
            eventWindow.poll();
        }
    }

    // New methods for rate calculations
    public double getCurrentRate() {
        if (eventWindow.isEmpty()) return 0;
        
        double oldestTime = eventWindow.peek().timestamp;
        double newestTime = eventWindow.stream()
            .mapToDouble(e -> e.timestamp)
            .max()
            .orElse(oldestTime);
            
        double windowDurationHours = (newestTime - oldestTime) / 3600000;
        if (windowDurationHours <= 0) return 0;
        
        return eventWindow.stream()
            .mapToInt(e -> e.count)
            .sum() / windowDurationHours;
    }

    public double getAverageRate() {
        if (eventWindow.isEmpty()) return 0;
        
        double firstTime = eventWindow.peek().timestamp;
        double lastTime = eventWindow.stream()
            .mapToDouble(e -> e.timestamp)
            .max()
            .orElse(firstTime);
            
        double totalHours = (lastTime - firstTime) / 3600000;
        if (totalHours <= 0) return 0;
        
        return eventWindow.stream()
            .mapToInt(e -> e.count)
            .sum() / totalHours;
    }

    // Existing methods remain unchanged
    public void printHourlyCounts() {
        System.out.println("*********** " + name + " *************");
        for (int i = 0; i < 24; i++) {
            System.out.println((i+1) + "-" + hourlyCount[i]);
        }
    }

    public long getMax() {
        long max = -1;
        for (long i : hourlyCount) {
            if (i > max) max = i;
        }
        return max;
    }

    public long[] getHourlyCount() {
        return hourlyCount;
    }
    
    // New helper methods
    public int getTotalEvents() {
        return eventWindow.stream().mapToInt(e -> e.count).sum();
    }
    
    public double getFirstEventTime() {
        return eventWindow.isEmpty() ? 0 : eventWindow.peek().timestamp;
    }
    
    public double getLastEventTime() {
        return eventWindow.stream()
            .mapToDouble(e -> e.timestamp)
            .max()
            .orElse(0);
    }
}