import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LeaveRequestsService } from './leave-requests.service';

describe('LeaveRequestsService', () => {
  let service: LeaveRequestsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(LeaveRequestsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads leave requests from the API', () => {
    service.getRequests().subscribe();

    const request = http.expectOne('http://localhost:5080/api/leave-requests');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });
});
