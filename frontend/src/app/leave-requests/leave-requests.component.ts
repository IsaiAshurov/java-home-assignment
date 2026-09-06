import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { Employee, LeaveRequest } from '../models/leave-request.model';

function dateRangeValidator(control: AbstractControl): ValidationErrors | null {
  const start = control.get('startDate')?.value;
  const end = control.get('endDate')?.value;
  return start && end && start > end ? { dateRange: true } : null;
}

// NOTE: This component was written quickly for a POC.
// It talks to the API directly, manages state by hand and uses `any` everywhere.
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

  private apiUrl = 'http://localhost:5080/api/leave-requests';
  private readonly fb = inject(FormBuilder);

  readonly leaveTypes = [
    { value: 0, label: 'Vacation' },
    { value: 1, label: 'Sick' },
    { value: 2, label: 'Unpaid' }
  ];

  readonly form = this.fb.group({
    employeeId: this.fb.control<number | null>(null, Validators.required),
    type: this.fb.control<number | null>(null, Validators.required),
    startDate: this.fb.control('', Validators.required),
    endDate: this.fb.control('', Validators.required)
  }, { validators: dateRangeValidator });

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
    this.http.get<Employee[]>('http://localhost:5080/api/employees')
      .subscribe((employees) => this.employees = employees);
  }

  load(): void {
    this.loading = true;
    this.http.get<LeaveRequest[]>(this.apiUrl).subscribe((data) => {
      this.requests = data;
      this.loading = false;
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

    this.http.post<LeaveRequest>(this.apiUrl, this.form.getRawValue()).subscribe({
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

    this.http.post<LeaveRequest>(`${this.apiUrl}/${id}/approve`, {})
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

  typeLabel(type: number): string {
    if (type == 0) return 'Vacation';
    if (type == 1) return 'Sick';
    return 'Unpaid';
  }

  statusLabel(status: number): string {
    if (status == 0) return 'Pending';
    if (status == 1) return 'Approved';
    return 'Rejected';
  }
}
