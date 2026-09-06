package com.example.leavemanagement;

import com.example.leavemanagement.controller.LeaveRequestsController;
import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

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
