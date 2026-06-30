import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AssistantService } from './assistant.service';
import { AssistantChatResponse } from '../api/models';

describe('AssistantService', () => {
  let service: AssistantService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AssistantService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POSTs the message to /assistant/chat with an Idempotency-Key and returns the reply', async () => {
    const reply: AssistantChatResponse = { reply: 'Here is a draft.', proposedActions: [] };
    const done = service.chat('draft a description');

    const req = http.expectOne('/api/v1/assistant/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ message: 'draft a description' });
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    req.flush(reply);

    expect(await done).toEqual(reply);
  });

  it('includes context in the body when provided', () => {
    service.chat('recommend scopes', { env: 'prod' });
    const req = http.expectOne('/api/v1/assistant/chat');
    expect(req.request.body).toEqual({ message: 'recommend scopes', context: { env: 'prod' } });
    req.flush({ reply: 'ok', proposedActions: [] });
  });

  it('propagates the error response (e.g. the v1 501 stub) to the caller', async () => {
    const done = service.chat('hello');
    http.expectOne('/api/v1/assistant/chat').flush(
      { status: 501, title: 'Not Implemented', detail: 'The assistant is not implemented in v1.' },
      { status: 501, statusText: 'Not Implemented' },
    );
    await expectAsync(done).toBeRejected();
  });
});
