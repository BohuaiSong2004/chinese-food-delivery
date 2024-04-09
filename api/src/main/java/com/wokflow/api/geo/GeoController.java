package com.wokflow.api.geo;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.common.ApiException;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GeoController {
    private final ServiceArea area;
    private final AddressBook addresses;

    public GeoController(ServiceArea area, AddressBook addresses) {
        this.area = area;
        this.addresses = addresses;
    }

    /** Public: lets a visitor see delivery fees and times for a ZIP before signing up. */
    @GetMapping("/api/geo/zips")
    Collection<ServiceArea.Place> zips() { return area.places(); }

    @GetMapping("/api/geo/zips/{zip}")
    ServiceArea.Place zip(@PathVariable String zip) {
        return area.place(zip).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "outside_service_area",
                "We don't deliver to " + zip + " yet"));
    }

    @GetMapping("/api/me/addresses")
    List<AddressBook.Address> mine() { return addresses.list(CurrentUser.require()); }

    @PostMapping("/api/me/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    AddressBook.Address add(@Valid @RequestBody AddressBook.Input in) { return addresses.add(CurrentUser.require(), in); }

    @PutMapping("/api/me/addresses/{id}")
    AddressBook.Address update(@PathVariable long id, @Valid @RequestBody AddressBook.Input in) {
        return addresses.update(CurrentUser.require(), id, in);
    }

    @DeleteMapping("/api/me/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long id) { addresses.remove(CurrentUser.require(), id); }
}
