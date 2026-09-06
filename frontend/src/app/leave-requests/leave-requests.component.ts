import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { CreateLeaveRequest, Employee, LeaveRequest, LeaveStatus, LeaveType } from '../models/leave-request.model';
import { LeaveRequestsService } from '../services/leave-requests.service';

function dateRangeValidator(control: AbstractControl): ValidationErrors | null {
  const start = control.get('startDate')?.value;
  const end = control.get('endDate')?.value;
  return start && end && start > end ? { dateRange: true } : null;
}

@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  requests: LeaveRequest[] = [];
  employees: Employee[] = [];
  loading = false;
  submitting = false;
  formMessage = '';
  formError = '';
  readonly approvingIds = new Set<number>();
  readonly approvalMessages: Record<number, string> = {};
  readonly approvalErrors: Record<number, string> = {};

  private readonly fb = inject(FormBuilder);

  readonly leaveTypes = [
    { value: LeaveType.Vacation, label: 'Vacation' },
    { value: LeaveType.Sick, label: 'Sick' },
    { value: LeaveType.Unpaid, label: 'Unpaid' }
  ];

  readonly form = this.fb.group({
    employeeId: this.fb.control<number | null>(null, Validators.required),
    type: this.fb.control<LeaveType | null>(null, Validators.required),
    startDate: this.fb.control('', Validators.required),
    endDate: this.fb.control('', Validators.required)
  }, { validators: dateRangeValidator });

  constructor(private leaveRequests: LeaveRequestsService) {}

  ngOnInit(): void {
    this.loading = true;
    forkJoin({
      requests: this.leaveRequests.getRequests(),
      employees: this.leaveRequests.getEmployees()
    }).pipe(finalize(() => this.loading = false))
      .subscribe(({ requests, employees }) => {
        this.requests = requests;
        this.employees = employees;
      });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.formMessage = '';
    this.formError = '';

    this.leaveRequests.createRequest(this.form.getRawValue() as CreateLeaveRequest).subscribe({
      next: (request) => {
        request.employee = this.employees.find((employee) => employee.id === request.employeeId);
        this.requests.unshift(request);
        this.form.reset();
        this.submitting = false;
        this.formMessage = 'Leave request created successfully.';
      },
      error: (error: HttpErrorResponse) => {
        this.submitting = false;
        this.formError = typeof error.error === 'string' ? error.error : 'Could not create leave request.';
      }
    });
  }

  // Wired up by the candidate as part of the assignment.
  approve(id: number): void {
    if (this.approvingIds.has(id)) return;

    this.approvingIds.add(id);
    delete this.approvalMessages[id];
    delete this.approvalErrors[id];

    this.leaveRequests.approveRequest(id)
      .pipe(finalize(() => this.approvingIds.delete(id)))
      .subscribe({
        next: (approved) => {
          const index = this.requests.findIndex((request) => request.id === id);
          if (index !== -1) {
            this.requests[index] = { ...approved, employee: approved.employee ?? this.requests[index].employee };
          }
          this.approvalMessages[id] = 'Request approved successfully.';
        },
        error: (error: HttpErrorResponse) => {
          this.approvalErrors[id] = this.approvalErrorMessage(error.status);
        }
      });
  }

  private approvalErrorMessage(status: number): string {
    if (status === 400) return 'Not enough vacation days.';
    if (status === 404) return 'Request not found.';
    if (status === 409) return 'Request already processed.';
    return 'Please try again.';
  }

  typeLabel(type: LeaveType): string {
    if (type === LeaveType.Vacation) return 'Vacation';
    if (type === LeaveType.Sick) return 'Sick';
    return 'Unpaid';
  }

  statusLabel(status: LeaveStatus): string {
    if (status === LeaveStatus.Pending) return 'Pending';
    if (status === LeaveStatus.Approved) return 'Approved';
    return 'Rejected';
  }
}
