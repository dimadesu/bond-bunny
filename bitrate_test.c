#include <stdio.h>
#include <time.h>
#include <stdint.h>
#include <stdlib.h>

// Test the bitrate calculation logic
#define BITRATE_WINDOW_SECONDS 5

typedef struct {
    uint64_t bytes_sent_total;
    uint64_t bytes_sent_window;
    time_t last_rate_update;
} test_conn_t;

void update_bitrate_calculations(test_conn_t* c, uint64_t bytes_sent) {
    time_t now = time(NULL);
    
    // Initialize if this is the first call
    if (c->last_rate_update == 0) {
        c->last_rate_update = now;
        c->bytes_sent_window = 0;
    }
    
    // Add bytes to totals
    c->bytes_sent_total += bytes_sent;
    c->bytes_sent_window += bytes_sent;
    
    // Reset window if enough time has passed
    if (now - c->last_rate_update >= BITRATE_WINDOW_SECONDS) {
        c->bytes_sent_window = 0;
        c->last_rate_update = now;
    }
}

double calculate_bitrate_mbps(test_conn_t* c) {
    time_t now = time(NULL);
    if (c->last_rate_update == 0 || c->bytes_sent_window == 0) return 0.0;
    
    double elapsed = (double)(now - c->last_rate_update);
    if (elapsed <= 0) elapsed = 1.0; // Avoid division by zero
    
    // Convert bytes per second to Mbps (Megabits per second)
    double bytes_per_sec = c->bytes_sent_window / elapsed;
    double mbps = (bytes_per_sec * 8.0) / (1024.0 * 1024.0);
    
    return mbps;
}

int main() {
    printf("Testing bitrate calculations...\n");
    
    test_conn_t conn1 = {0, 0, 0};
    test_conn_t conn2 = {0, 0, 0};
    
    // Simulate sending 1MB over 5 seconds on conn1
    for (int i = 0; i < 5; i++) {
        update_bitrate_calculations(&conn1, 200000); // 200KB per call
        printf("Conn1 after %d seconds: %.2f Mbps\n", i+1, calculate_bitrate_mbps(&conn1));
        sleep(1);
    }
    
    // Simulate sending 500KB instantly on conn2
    update_bitrate_calculations(&conn2, 500000);
    printf("Conn2 instant: %.2f Mbps\n", calculate_bitrate_mbps(&conn2));
    
    printf("Test completed!\n");
    return 0;
}