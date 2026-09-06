package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequests;
    private final EmployeeRepository employees;

    public LeaveRequestService(LeaveRequestRepository leaveRequests, EmployeeRepository employees) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
    }

    public List<LeaveRequest> getAll() {
        return leaveRequests.findAllByOrderByStartDateDesc();
    }

    public List<LeaveRequest> search(String name) {
        return leaveRequests.findByEmployee_NameContainingIgnoreCase(name);
    }

    @Transactional
    public LeaveRequest create(CreateLeaveRequestDto dto) {
        Employee employee = employees.findById(dto.getEmployeeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
        int days = calculateDays(dto);

        if (dto.getType() == LeaveType.VACATION
                && usedVacationDays(dto.getEmployeeId(), dto.getStartDate()) + days > employee.getAnnualQuota()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough vacation balance");
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);
        return leaveRequests.save(request);
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
            if (usedVacationDays(request.getEmployeeId(), request.getStartDate()) + request.getDays() > employee.getAnnualQuota()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough vacation balance");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        return leaveRequests.save(request);
    }

    private int calculateDays(CreateLeaveRequestDto dto) {
        return (int) ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1;
    }

    private int usedVacationDays(Long employeeId, LocalDate requestDate) {
        LocalDate firstDay = requestDate.withDayOfYear(1);
        LocalDate lastDay = requestDate.withDayOfYear(requestDate.lengthOfYear());
        return leaveRequests
                .findByEmployeeIdAndTypeAndStatusAndStartDateBetween(
                        employeeId, LeaveType.VACATION, LeaveStatus.APPROVED, firstDay, lastDay)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();
    }
}
