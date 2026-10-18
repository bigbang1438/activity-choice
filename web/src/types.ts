export type RuleStatus = "verified" | "unknown";
export type Choice = "want" | "okay" | "avoid";
export interface Session {
  user: null | { username: string };
  csrfToken: string;
  csrfHeader: string;
}
export interface CandidateInput {
  title: string;
  address: string;
  timeWindow: string;
  sourceUrl: string;
  verifiedAt: string;
  openingText: string;
  openingStatus: RuleStatus;
  feeText: string;
  feeStatus: RuleStatus;
  reservationText: string;
  reservationStatus: RuleStatus;
  internalNote: string;
}
export interface Candidate extends CandidateInput {
  id: string;
  version: number;
  createdBy: string;
}
export interface Decision {
  mainId: string;
  backupId: string;
  meetingTime: string;
  meetingPlace: string;
  publicNote: string;
}
export interface PublicationSummary {
  id: string;
  version: number;
  publishedAt: string;
  revoked: boolean;
  isLatest: boolean;
}
export interface GroupSummary {
  id: string;
  name: string;
  eventDate: string;
  version: number;
  role: string;
}
export interface Group extends GroupSummary {
  members: { username: string; role: string }[];
  candidates: Candidate[];
  preferences: { candidateId: string; username: string; choice: Choice }[];
  decision: Decision | null;
  publications: PublicationSummary[];
}
export interface Invitation {
  id: string;
  token: string;
  expiresAt: string;
  revoked?: boolean;
}
export type PublicCandidate = Omit<CandidateInput, "internalNote">;
export interface Publication {
  id: string;
  version: number;
  publishedAt: string;
  isLatest: boolean;
  content: {
    groupName: string;
    eventDate: string;
    main: PublicCandidate;
    backup: PublicCandidate;
    meetingTime: string;
    meetingPlace: string;
    publicNote: string;
  };
}
