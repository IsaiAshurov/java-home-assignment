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
});
