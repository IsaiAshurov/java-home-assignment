import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LeaveRequestsComponent } from './leave-requests.component';

describe('LeaveRequestsComponent', () => {
  let fixture: ComponentFixture<LeaveRequestsComponent>;
  let component: LeaveRequestsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LeaveRequestsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(LeaveRequestsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('http://localhost:5080/api/leave-requests').flush([]);
    http.expectOne('http://localhost:5080/api/employees').flush([]);
  });

  afterEach(() => http.verify());

  it('rejects an end date before the start date', () => {
    component.form.setValue({
      employeeId: 1,
      type: 0,
      startDate: '2026-06-03',
      endDate: '2026-06-01'
    });

    expect(component.form.hasError('dateRange')).toBeTrue();
  });

  it('rejects dates in different years', () => {
    component.form.setValue({
      employeeId: 1,
      type: 0,
      startDate: '2025-12-30',
      endDate: '2026-01-10'
    });

    expect(component.form.hasError('crossYear')).toBeTrue();
  });

  it('shows an error when initial loading fails', () => {
    const failedFixture = TestBed.createComponent(LeaveRequestsComponent);
    failedFixture.detectChanges();

    http.expectOne('http://localhost:5080/api/employees').flush([]);
    http.expectOne('http://localhost:5080/api/leave-requests')
      .flush('Server error', { status: 500, statusText: 'Server Error' });
    failedFixture.detectChanges();

    expect(failedFixture.nativeElement.textContent).toContain('Could not load data.');
  });

  it('submits a valid leave request', () => {
    component.form.setValue({
      employeeId: 2,
      type: 0,
      startDate: '2026-06-01',
      endDate: '2026-06-03'
    });

    component.submit();

    const request = http.expectOne('http://localhost:5080/api/leave-requests');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(component.form.getRawValue());
    request.flush({ id: 10, ...component.form.getRawValue(), status: 0, days: 3 });

    expect(component.requests[0].id).toBe(10);
  });

  it('updates only the approved request without reloading the list', () => {
    component.requests = [{
      id: 7,
      employeeId: 2,
      type: 0,
      startDate: '2026-07-01',
      endDate: '2026-07-02',
      status: 0,
      days: 2
    }];

    component.approve(7);

    const request = http.expectOne('http://localhost:5080/api/leave-requests/7/approve');
    expect(request.request.method).toBe('POST');
    request.flush({ ...component.requests[0], status: 1 });

    expect(component.requests[0].status).toBe(1);
  });

  it('shows an approval error and clears loading', () => {
    component.approve(8);
    expect(component.approvingIds.has(8)).toBeTrue();

    http.expectOne('http://localhost:5080/api/leave-requests/8/approve')
      .flush('Leave request is already processed', { status: 409, statusText: 'Conflict' });

    expect(component.approvalErrors[8]).toBe('Request already processed.');
    expect(component.approvingIds.has(8)).toBeFalse();
  });
});
