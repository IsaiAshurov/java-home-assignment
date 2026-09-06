package com.example.leavemanagement;

import com.example.leavemanagement.controller.LeaveRequestsController;
import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LeaveRequestsController controller;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Test
    void create_WithinQuota_Succeeds() {
        // Arrange
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(20);
        employees.save(emp);

        long before = leaveRequests.count();

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3)); // 3 days, well within the quota

        // Act
        ResponseEntity<?> result = controller.create(dto);

        // Assert
        assertTrue(result.getStatusCode().is2xxSuccessful());
        assertEquals(before + 1, leaveRequests.count());
    }

    @Test
    void create_ExceedingRemainingQuota_IsRejected() {
        Employee emp = new Employee();
        emp.setName("Nearly Out Of Leave");
        emp.setAnnualQuota(20);
        employees.save(emp);

        LeaveRequest approved = new LeaveRequest();
        approved.setEmployeeId(emp.getId());
        approved.setType(LeaveType.VACATION);
        approved.setStartDate(LocalDate.of(2026, 1, 1));
        approved.setEndDate(LocalDate.of(2026, 1, 18));
        approved.setDays(18);
        approved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(approved);

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.create(dto));

        assertTrue(exception.getStatusCode().is4xxClientError());
    }

    @Test
    void create_PreviousYearVacation_DoesNotReduceCurrentYearQuota() {
        Employee emp = saveEmployee("New Annual Balance", 20);

        LeaveRequest previousYear = new LeaveRequest();
        previousYear.setEmployeeId(emp.getId());
        previousYear.setType(LeaveType.VACATION);
        previousYear.setStartDate(LocalDate.of(2025, 1, 1));
        previousYear.setEndDate(LocalDate.of(2025, 1, 20));
        previousYear.setDays(20);
        previousYear.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(previousYear);

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 1, 1));
        dto.setEndDate(LocalDate.of(2026, 1, 20));

        assertDoesNotThrow(() -> leaveRequestService.create(dto));
    }

    @Test
    void approve_PendingRequest_Succeeds() throws Exception {
        Employee emp = new Employee();
        emp.setName("Approver Test");
        emp.setAnnualQuota(20);
        employees.save(emp);

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(emp.getId());
        request.setType(LeaveType.VACATION);
        request.setStartDate(LocalDate.of(2026, 4, 1));
        request.setEndDate(LocalDate.of(2026, 4, 2));
        request.setDays(2);
        request.setStatus(LeaveStatus.PENDING);
        leaveRequests.save(request);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", request.getId()))
                .andExpect(status().isOk());

        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_MissingRequest_ReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/leave-requests/{id}/approve", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void approve_AlreadyProcessedRequest_ReturnsConflict() throws Exception {
        Employee emp = saveEmployee("Processed Request", 20);
        LeaveRequest request = saveRequest(emp, 2, LeaveStatus.APPROVED);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", request.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void approve_RejectedRequest_ReturnsConflict() throws Exception {
        Employee emp = saveEmployee("Rejected Request", 20);
        LeaveRequest request = saveRequest(emp, 2, LeaveStatus.REJECTED);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", request.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void approve_ExceedingQuota_ReturnsBadRequest() throws Exception {
        Employee emp = saveEmployee("Insufficient Balance", 20);
        saveRequest(emp, 18, LeaveStatus.APPROVED);
        LeaveRequest pending = saveRequest(emp, 3, LeaveStatus.PENDING);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", pending.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_TreatsSqlMetacharactersAsPlainText() throws Exception {
        mockMvc.perform(get("/api/leave-requests/search").param("name", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void create_MissingRequiredFields_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_EndDateBeforeStartDate_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeId": 1,
                                  "type": "VACATION",
                                  "startDate": "2026-08-03",
                                  "endDate": "2026-08-01"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_DatesInDifferentYears_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeId": 1,
                                  "type": "VACATION",
                                  "startDate": "2025-12-30",
                                  "endDate": "2026-01-10"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approve_ConcurrentRequests_DoesNotExceedQuota() throws Exception {
        Employee emp = saveEmployee("Concurrent Approvals", 20);
        LeaveRequest alreadyApproved = saveRequest(emp, 18, LeaveStatus.APPROVED);
        LeaveRequest first = saveRequest(emp, 2, LeaveStatus.PENDING);
        LeaveRequest second = saveRequest(emp, 2, LeaveStatus.PENDING);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstStatus = executor.submit(() -> approveAfter(start, first.getId()));
            Future<Integer> secondStatus = executor.submit(() -> approveAfter(start, second.getId()));
            start.countDown();

            List<Integer> statuses = List.of(firstStatus.get(), secondStatus.get());
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, statuses.stream().filter(status -> status == 400).count());

            int usedDays = List.of(alreadyApproved, first, second).stream()
                    .map(request -> leaveRequests.findById(request.getId()).orElseThrow())
                    .filter(request -> request.getStatus() == LeaveStatus.APPROVED)
                    .mapToInt(LeaveRequest::getDays)
                    .sum();
            assertEquals(20, usedDays);
        } finally {
            executor.shutdownNow();
        }
    }

    private int approveAfter(CountDownLatch start, Long requestId) throws InterruptedException {
        start.await();
        try {
            leaveRequestService.approve(requestId);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private Employee saveEmployee(String name, int quota) {
        Employee emp = new Employee();
        emp.setName(name);
        emp.setAnnualQuota(quota);
        return employees.save(emp);
    }

    private LeaveRequest saveRequest(Employee employee, int days, LeaveStatus status) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employee.getId());
        request.setType(LeaveType.VACATION);
        request.setStartDate(LocalDate.of(2026, 5, 1));
        request.setEndDate(LocalDate.of(2026, 5, days));
        request.setDays(days);
        request.setStatus(status);
        return leaveRequests.save(request);
    }
}
