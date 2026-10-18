package local.activity;

final class ApiError extends RuntimeException {
    final int status;
    final String code;
    ApiError(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static ApiError invalid(String message) { return new ApiError(400,"invalid_input",message); }
    static ApiError forbidden() { return new ApiError(403,"forbidden","当前账号无权操作此活动"); }
    static ApiError missing() { return new ApiError(404,"not_found","内容不存在"); }
    static ApiError conflict(String message) { return new ApiError(409,"conflict",message); }
}
