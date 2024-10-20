package com.fpt.vanguard.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class UngLuongDtoResponse {
    private String maUngLuong;
    private int nam;
    private int thang;
    private int ngay;
    private Double soTien;
    private Boolean trangThai;
    private String maNhanVien;
}