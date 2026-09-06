package com.example.leavemanagement.service;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequests;
    private final EmployeeRepository employees;

    public LeaveRequestService(LeaveRequestRepository leaveRequests, EmployeeRepository employees) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
    }

    @Transactional
    public LeaveRequest approve(Long id) {
        LeaveRequest request = leaveRequests.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leave request is already processed");
        }

        Employee employee = employees.findByIdForUpdate(request.getEmployeeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        if (request.getType() == LeaveType.VACATION) {
            int used = leaveRequests
                    .findByEmployeeIdAndTypeAndStatus(request.getEmployeeId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                    .stream()
                    .mapToInt(LeaveRequest::getDays)
                    .sum();

            if (used + request.getDays() > employee.getAnnualQuota()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough vacation balance");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        return leaveRequests.save(request);
    }
}
